package io.github.lujinxin.nextep.systemui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.SurfaceControl
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.framework.TaskSurfaceCompat
import io.github.lujinxin.nextep.framework.TaskInfoCompat
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

/** Finish owned exchanges and main-display rotations through Shell's native fast path. */
internal object WorkspaceDisplayTransitionHook {
    @Volatile private var displayManager: DisplayManager? = null
    @Volatile private var homePackage: String? = null
    private val immediateRotation = ThreadLocal<Any>()
    @Volatile private var systemWindowFlag = 0

    fun initialize(context: Context) {
        displayManager = context.getSystemService(DisplayManager::class.java)
        homePackage = TriggerBroadcastContract.resolveHomePackage(context)
    }

    fun install(module: XposedModule, loader: ClassLoader) {
        systemWindowFlag = runCatching {
            loader.loadClass("android.window.TransitionInfo").getField("FLAG_IS_SYSTEM_WINDOW").getInt(null)
        }.getOrDefault(0)
        val handlerClass = loader.loadClass("com.android.wm.shell.transition.DefaultTransitionHandler")
        val startAnimation = handlerClass.declaredMethods.single {
            it.name == "startAnimation" && it.parameterTypes.size == 5 &&
                it.parameterTypes[1].name == "android.window.TransitionInfo" &&
                it.parameterTypes[2] == SurfaceControl.Transaction::class.java
        }.apply { isAccessible = true }
        module.hook(startAnimation).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val rotation = runCatching {
                    val info = chain.args.getOrNull(1) ?: return@runCatching false
                    if (!SystemUiRuntime.isWorkspaceActive()) return@runCatching false
                    val rotation = rotationTarget(info) ?: return@runCatching false
                    val transaction = chain.args.getOrNull(2) as? SurfaceControl.Transaction
                        ?: return@runCatching false
                    val finishTransaction = chain.args.getOrNull(3) as? SurfaceControl.Transaction
                        ?: return@runCatching false
                    val generation = TaskSurfaceCompat.beginDisplayRotation(rotation, transaction, finishTransaction)
                        ?: return@runCatching false
                    SystemUiRuntime.prepareDisplayRotation(rotation, generation)
                    true
                }.onFailure {
                    NeXtepLog.warn("workspace_display_transition", "Unable to synchronize display rotation", it)
                }.getOrDefault(false)
                if (!rotation) return chain.proceed()
                val previous = immediateRotation.get()
                immediateRotation.set(checkNotNull(chain.args.getOrNull(1)))
                return try { chain.proceed() } finally {
                    if (previous == null) immediateRotation.remove() else immediateRotation.set(previous)
                }
            }
        })
        // Verified against the captured ColorOS implementation: this check precedes
        // background/snapshot animation creation. Its native fast path applies startT
        // and invokes the finish callback itself. Omitting only loadAnimation leaves
        // background animation active and does not complete the exchange immediately.
        val method = handlerClass
            .declaredMethods.single {
                it.name == "isAnimationsDisabledForAnyDisplay" &&
                    it.returnType == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes.singleOrNull()?.name == "android.window.TransitionInfo"
            }.apply { isAccessible = true }
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (immediateRotation.get() != null && immediateRotation.get() === chain.args.firstOrNull()) {
                    NeXtepLog.info("workspace_display_transition", "Retained panel buffers during display rotation")
                    return true
                }
                val info = chain.args.firstOrNull()
                val exchange = runCatching {
                    info != null && SystemUiRuntime.isWorkspaceActive() && isWorkspaceExchange(info)
                }.getOrDefault(false)
                if (!exchange) return chain.proceed()
                NeXtepLog.info("workspace_display_transition", "Using native immediate finish for workspace exchange")
                return true
            }
        })
        NeXtepLog.info("workspace_display_transition", "Installed scoped native exchange completion")
    }

    private fun rotationTarget(info: Any): Int? {
        val type = call(info, "getType") as? Int ?: return null
        if (type !in setOf(1, 2, 3, 4, TRANSIT_CHANGE) || call(info, "isKeyguardGoingAway") == true) return null
        val changes = call(info, "getChanges") as? List<*> ?: return null
        val display = changes.filterNotNull().singleOrNull {
            ((call(it, "getFlags") as? Int ?: 0) and FLAG_IS_DISPLAY) != 0 &&
                call(it, "getEndDisplayId") == Display.DEFAULT_DISPLAY &&
                call(it, "getStartRotation") != call(it, "getEndRotation")
        } ?: return null
        val displayToken = call(display, "getContainer") ?: return null
        val containers = changes.filterNotNull().mapNotNull { change ->
            call(change, "getContainer")?.let { it to change }
        }.toMap()
        val parents = containers.mapValues { (_, change) -> call(change, "getParent") }
        val mainContainers = changes.filterNotNull().mapNotNull { change ->
            val task = call(change, "getTaskInfo") as? ActivityManager.RunningTaskInfo ?: return@mapNotNull null
            if (TaskSurfaceCompat.ownsMainTask(task.taskId)) call(change, "getContainer") else null
        }.toSet()
        fun isMainContainer(container: Any?): Boolean =
            container in mainContainers || TaskSurfaceCompat.ownsMainContainer(container)
        fun isMainContent(change: Any): Boolean {
            val container = call(change, "getContainer") ?: return false
            return WorkspaceDisplayRotationPolicy.isMainContent(container, parents, ::isMainContainer)
        }
        val ancestors = mutableSetOf<Any>()
        changes.filterNotNull().forEach { change ->
            val task = call(change, "getTaskInfo") as? ActivityManager.RunningTaskInfo ?: return@forEach
            if (!TaskSurfaceCompat.ownsMainTask(task.taskId)) return@forEach
            var parent = call(change, "getParent")
            val visited = mutableSetOf<Any>()
            while (parent != null && parent != displayToken && visited.add(parent)) {
                ancestors += parent
                parent = containers[parent]?.let { call(it, "getParent") }
            }
            if (parent != displayToken) return null
        }
        val summaries = changes.map { change ->
            change ?: return null
            val mode = call(change, "getMode") as? Int ?: return null
            val start = call(change, "getStartRotation") as? Int ?: return null
            val end = call(change, "getEndRotation") as? Int ?: return null
            val task = call(change, "getTaskInfo") as? ActivityManager.RunningTaskInfo
            val flags = call(change, "getFlags") as? Int ?: return null
            val startDisplay = call(change, "getStartDisplayId") as? Int ?: return null
            val endDisplay = call(change, "getEndDisplayId") as? Int ?: return null
            val mainContent = isMainContent(change)
            val kind = if (task != null) {
                val state = TaskInfoCompat.readWindowState(task)
                when {
                    state == null || state.vendorWindowed || state.windowingMode != 1 ||
                        state.displayId != Display.DEFAULT_DISPLAY ||
                        startDisplay !in -1..Display.DEFAULT_DISPLAY || endDisplay !in -1..Display.DEFAULT_DISPLAY ->
                        WorkspaceDisplayRotationPolicy.Kind.OTHER
                    TaskSurfaceCompat.ownsMainTask(task.taskId) -> WorkspaceDisplayRotationPolicy.Kind.MAIN
                    mainContent -> WorkspaceDisplayRotationPolicy.Kind.MAIN_CONTENT
                    homePackage != null && (task.topActivity ?: task.baseActivity)?.packageName == homePackage ->
                        WorkspaceDisplayRotationPolicy.Kind.HOME
                    else -> WorkspaceDisplayRotationPolicy.Kind.OTHER
                }
            } else when {
                (mainContent || isMainContainer(call(change, "getContainer"))) &&
                    (startDisplay !in -1..Display.DEFAULT_DISPLAY || endDisplay !in -1..Display.DEFAULT_DISPLAY) ->
                    WorkspaceDisplayRotationPolicy.Kind.OTHER
                isMainContainer(call(change, "getContainer")) -> WorkspaceDisplayRotationPolicy.Kind.MAIN
                mainContent -> WorkspaceDisplayRotationPolicy.Kind.MAIN_CONTENT
                startDisplay != endDisplay -> WorkspaceDisplayRotationPolicy.Kind.OTHER
                flags and FLAG_IS_DISPLAY != 0 -> WorkspaceDisplayRotationPolicy.Kind.DISPLAY
                flags and FLAG_IS_WALLPAPER != 0 -> WorkspaceDisplayRotationPolicy.Kind.WALLPAPER
                call(change, "getContainer") in ancestors ->
                    // These ancestors were traced from the owned task to display 0.
                    // Immersive players can change their inset/zoom bounds too, so
                    // requiring them to equal the full display rejects valid rotations.
                    WorkspaceDisplayRotationPolicy.Kind.DISPLAY_CONTAINER
                systemWindowFlag != 0 && flags and systemWindowFlag != 0 ->
                    WorkspaceDisplayRotationPolicy.Kind.DISPLAY_DECOR
                else -> WorkspaceDisplayRotationPolicy.Kind.OTHER
            }
            val sameSize = if (kind == WorkspaceDisplayRotationPolicy.Kind.DISPLAY && start != end) {
                val before = call(change, "getStartAbsBounds") as? Rect ?: return null
                val after = call(change, "getEndAbsBounds") as? Rect ?: return null
                if ((end - start + 4) % 2 == 1) before.width() == after.height() && before.height() == after.width()
                else before.width() == after.width() && before.height() == after.height()
            } else true
            val displayId = if (kind == WorkspaceDisplayRotationPolicy.Kind.MAIN ||
                kind == WorkspaceDisplayRotationPolicy.Kind.MAIN_CONTENT) Display.DEFAULT_DISPLAY else endDisplay
            WorkspaceDisplayRotationPolicy.Change(kind, mode, displayId, start, end, sameSize)
        }
        return WorkspaceDisplayRotationPolicy.targetRotation(type, summaries)
    }

    private fun isWorkspaceExchange(info: Any): Boolean {
        val type = call(info, "getType") as? Int ?: return false
        if (call(info, "isKeyguardGoingAway") == true) return false
        val changes = call(info, "getChanges") as? List<*> ?: return false
        val summaries = changes.map { change ->
            change ?: return false
            val mode = call(change, "getMode") as? Int ?: return false
            val task = call(change, "getTaskInfo") as? ActivityManager.RunningTaskInfo
            val rotates = call(change, "getStartRotation") != call(change, "getEndRotation")
            val kind = if (task != null) {
                val state = TaskInfoCompat.readWindowState(task)
                if (state == null || state.vendorWindowed || state.windowingMode != 1) {
                    WorkspaceDisplayExchangePolicy.Kind.OTHER
                } else if (WorkspaceDisplayExchangePolicy.isExchangeMode(mode) &&
                    isExchanging(change, task.taskId)) {
                    WorkspaceDisplayExchangePolicy.Kind.EXCHANGE
                } else if (state.displayId == Display.DEFAULT_DISPLAY && homePackage != null &&
                    (task.topActivity ?: task.baseActivity)?.packageName == homePackage
                ) {
                    WorkspaceDisplayExchangePolicy.Kind.HOME
                } else WorkspaceDisplayExchangePolicy.Kind.OTHER
            } else {
                val flags = call(change, "getFlags") as? Int ?: return false
                when {
                    flags and FLAG_IS_WALLPAPER != 0 -> WorkspaceDisplayExchangePolicy.Kind.WALLPAPER
                    flags and FLAG_IS_DISPLAY != 0 && mode == TRANSIT_CHANGE &&
                        call(change, "getStartDisplayId") == call(change, "getEndDisplayId") &&
                        call(change, "getStartAbsBounds") == call(change, "getEndAbsBounds") -> {
                        val displayId = call(change, "getEndDisplayId") as? Int
                        when {
                            displayId == Display.DEFAULT_DISPLAY -> WorkspaceDisplayExchangePolicy.Kind.STATIONARY_DISPLAY
                            displayId != null && isSlotDisplay(displayId) -> WorkspaceDisplayExchangePolicy.Kind.STATIONARY_SLOT_DISPLAY
                            else -> WorkspaceDisplayExchangePolicy.Kind.OTHER
                        }
                    }
                    else -> WorkspaceDisplayExchangePolicy.Kind.OTHER
                }
            }
            WorkspaceDisplayExchangePolicy.Change(kind, rotates)
        }
        val exchange = WorkspaceDisplayExchangePolicy.shouldFinishImmediately(
            type, summaries, hasRecentSlotMove = TaskSurfaceCompat.hasRecentSlotMove(),
        )
        if (exchange) changes.forEach { change ->
            (change?.let { call(it, "getTaskInfo") } as? ActivityManager.RunningTaskInfo)
                ?.let { TaskSurfaceCompat.observeTaskInfo(it) }
        }
        return exchange
    }

    private fun isExchanging(change: Any, taskId: Int): Boolean {
        if (TaskSurfaceCompat.isDisplayExchanging(taskId)) return true
        val start = call(change, "getStartDisplayId") as? Int ?: return false
        val end = call(change, "getEndDisplayId") as? Int ?: return false
        if (start == end || (start != Display.DEFAULT_DISPLAY && end != Display.DEFAULT_DISPLAY)) return false
        return isSlotDisplay(if (start == Display.DEFAULT_DISPLAY) end else start)
    }

    private fun isSlotDisplay(displayId: Int): Boolean =
        displayManager?.getDisplay(displayId)?.name?.startsWith("NeXtep-slot-") == true

    private fun call(target: Any, name: String): Any? = target.javaClass.getMethod(name).invoke(target)

    private const val TRANSIT_CHANGE = 6
    private const val FLAG_IS_WALLPAPER = 2
    private const val FLAG_IS_DISPLAY = 32
}
