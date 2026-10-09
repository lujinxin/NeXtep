package io.github.lujinxin.nextep.systemui

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.framework.TaskSurfaceCompat
import io.github.lujinxin.nextep.framework.TaskInfoCompat
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

/** Finish workspace exchanges through Shell's existing no-animation path. */
internal object WorkspaceDisplayTransitionHook {
    @Volatile private var displayManager: DisplayManager? = null
    @Volatile private var homePackage: String? = null

    fun initialize(context: Context) {
        displayManager = context.getSystemService(DisplayManager::class.java)
        homePackage = TriggerBroadcastContract.resolveHomePackage(context)
    }

    fun install(module: XposedModule, loader: ClassLoader) {
        val handlerClass = loader.loadClass("com.android.wm.shell.transition.DefaultTransitionHandler")
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
