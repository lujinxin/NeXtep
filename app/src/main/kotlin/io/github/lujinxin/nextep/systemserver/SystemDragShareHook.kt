package io.github.lujinxin.nextep.systemserver

import android.content.Context
import android.database.ContentObserver
import android.graphics.Region
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.SurfaceControl
import android.view.WindowManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.config.WorkspaceConfigContract
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge as Bridge
import java.util.concurrent.atomic.AtomicBoolean

/** Keep ColorOS's bottom content portal out of workspace drags at both WM and native input. */
internal object SystemDragShareHook {
    private data class State(val active: Boolean = false,
        val enabled: Boolean = WorkspaceConfigContract.DEFAULT_BLOCK_SYSTEM_DRAG_SHARE_ENABLED)

    @Volatile private var state = State()
    private val initialized = AtomicBoolean(false)
    private val failureReported = AtomicBoolean(false)
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var observer: ContentObserver? = null
    private var workspaceObserver: ContentObserver? = null

    fun install(module: XposedModule, loader: ClassLoader) {
        val windowClass = loader.loadClass("com.android.server.wm.WindowState")
        val attrsField = windowClass.getDeclaredField("mAttrs").apply { isAccessible = true }
        val displayId = method(windowClass, "getDisplayId")
        val dragClass = loader.loadClass("com.android.server.wm.DragState")
        val validTargets = dragClass.declaredMethods.filter {
            it.name == "isValidDropTarget" && it.parameterTypes.firstOrNull() == windowClass &&
                it.returnType == Boolean::class.javaPrimitiveType
        }
        check(validTargets.isNotEmpty()) { "No compatible drag target validation" }

        val monitorClass = loader.loadClass("com.android.server.wm.InputMonitor")
        val handleClass = loader.loadClass("com.android.server.wm.InputWindowHandleWrapper")
        val populate = method(monitorClass, "populateInputWindowHandle", handleClass, windowClass)
        val setInputConfig = method(handleClass, "setInputConfigMasked",
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        val notTouchable = loader.loadClass("android.os.InputConfig").getField("NOT_TOUCHABLE").getInt(null)
        val setRegion = method(handleClass, "setTouchableRegion", Region::class.java)
        val replaceWithCrop = method(handleClass, "setReplaceTouchableRegionWithCrop", Boolean::class.javaPrimitiveType!!)
        val setCrop = method(handleClass, "setTouchableRegionCrop", SurfaceControl::class.java)
        val touchableRegion = method(windowClass, "getTouchableRegion", Region::class.java)

        val wmClass = loader.loadClass("com.android.server.wm.WindowManagerService")
        val contextField = wmClass.getDeclaredField("mContext").apply { isAccessible = true }
        val lockField = wmClass.getDeclaredField("mGlobalLock").apply { isAccessible = true }
        val rootField = wmClass.getDeclaredField("mRoot").apply { isAccessible = true }
        val getDisplay = method(rootField.type, "getDisplayContent", Int::class.javaPrimitiveType!!)
        val getMonitor = method(getDisplay.returnType, "getInputMonitor")
        val updateInput = method(monitorClass, "updateInputWindowsLw", Boolean::class.javaPrimitiveType!!)
        val systemReady = method(wmClass, "systemReady")

        fun blocked(window: Any?): Boolean {
            val current = state
            if (window == null || !current.active || !current.enabled) return false
            return runCatching {
                val attrs = attrsField.get(window) as? WindowManager.LayoutParams ?: return@runCatching false
                if (attrs.packageName != SystemDragSharePolicy.PORTAL_PACKAGE) return@runCatching false
                SystemDragSharePolicy.shouldBlock(current.active, current.enabled,
                    displayId.invoke(window) as Int, attrs.packageName, attrs.title?.toString())
            }.onFailure(::reportFailure).getOrDefault(false)
        }

        validTargets.forEach { target ->
            module.hook(target.apply { isAccessible = true }).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? =
                    if (blocked(chain.args.firstOrNull())) false else chain.proceed()
            })
        }
        module.hook(touchableRegion).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                if (blocked(chain.thisObject)) (chain.args[0] as? Region)?.setEmpty()
                return result
            }
        })
        module.hook(populate).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                if (!blocked(chain.args[1])) return result
                runCatching {
                    val handle = chain.args[0] ?: return@runCatching
                    // The real InputDispatcher hit test must also skip this window.
                    // Merely rejecting its DROP would leave the sidebar underneath unreachable.
                    setInputConfig.invoke(handle, notTouchable, notTouchable)
                    setRegion.invoke(handle, Region())
                    replaceWithCrop.invoke(handle, false)
                    setCrop.invoke(handle, null)
                }.onFailure(::reportFailure)
                return result
            }
        })
        dragClass.declaredMethods.filter { it.name == "sendDragStartedLocked" }
            .forEach { module.deoptimize(it) }
        runCatching {
            loader.loadClass("com.android.server.wm.InputMonitor\$UpdateInputForAllWindowsConsumer")
                .declaredMethods.filter { it.name == "accept" }.forEach { module.deoptimize(it) }
        }.onFailure { NeXtepLog.warn("system_drag_share", "Optional input consumer deoptimization unavailable", it) }

        module.hook(systemReady).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                if (!initialized.compareAndSet(false, true)) return result
                val service = chain.thisObject ?: return result
                handler.post {
                    runCatching {
                        val context = contextField.get(service) as Context
                        val resolver = context.contentResolver
                        fun refresh() {
                            val next = runCatching {
                                State(Bridge.isWorkspaceActive(context), Settings.Global.getInt(resolver,
                                    Bridge.BLOCK_SYSTEM_DRAG_SHARE_KEY,
                                    if (WorkspaceConfigContract.DEFAULT_BLOCK_SYSTEM_DRAG_SHARE_ENABLED) 1 else 0) == 1)
                            }.onFailure(::reportFailure).getOrDefault(State())
                            if (next == state) return
                            state = next
                            runCatching {
                                val lock = checkNotNull(lockField.get(service)) { "Window manager lock unavailable" }
                                synchronized(lock) {
                                    val root = rootField.get(service)
                                    val display = getDisplay.invoke(root, Display.DEFAULT_DISPLAY)
                                        ?: return@synchronized
                                    // Native population restores flags and regions from the untouched
                                    // LayoutParams when the switch is off or the workspace has exited.
                                    updateInput.invoke(getMonitor.invoke(display), true)
                                }
                            }.onFailure(::reportFailure)
                        }
                        observer = object : ContentObserver(handler) {
                            override fun onChange(selfChange: Boolean) = refresh()
                        }.also {
                            resolver.registerContentObserver(Settings.Global.getUriFor(Bridge.BLOCK_SYSTEM_DRAG_SHARE_KEY), false, it)
                        }
                        workspaceObserver = Bridge.observeWorkspaceActive(context, handler) { refresh() }
                    }.onFailure {
                        state = State()
                        reportFailure(it)
                    }
                }
                return result
            }
        })
        NeXtepLog.info("system_drag_share", "Installed scoped content portal drag suppression")
    }

    private fun method(type: Class<*>, name: String, vararg parameters: Class<*>) =
        generateSequence(type as Class<*>?) { it.superclass }.mapNotNull {
            runCatching { it.getDeclaredMethod(name, *parameters) }.getOrNull()
        }.first().apply { isAccessible = true }

    private fun reportFailure(error: Throwable) {
        if (failureReported.compareAndSet(false, true)) {
            NeXtepLog.warn("system_drag_share", "Content portal drag compatibility unavailable", error)
        }
    }
}
