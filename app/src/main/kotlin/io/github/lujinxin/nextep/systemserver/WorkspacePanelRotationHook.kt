package io.github.lujinxin.nextep.systemserver

import android.view.WindowManager
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

/** Retain workspace panel buffers through both seamless and ordinary app rotations. */
internal object WorkspacePanelRotationHook {
    private val titles = setOf("NeXtepTopBar", "NeXtepSidebar", "NeXtepContentPanel")
    private val reportedFailure = AtomicBoolean(false)

    fun install(module: XposedModule, loader: ClassLoader) {
        val windowClass = loader.loadClass("com.android.server.wm.WindowState")
        val controllerClass = loader.loadClass("com.android.server.wm.AsyncRotationController")
        val operationClass = loader.loadClass("com.android.server.wm.AsyncRotationController\$Operation")
        val attrs = windowClass.getDeclaredField("mAttrs").apply { isAccessible = true }
        val forceSeamless = windowClass.getDeclaredField("mForceSeamlesslyRotate").apply { isAccessible = true }
        val token = windowClass.getDeclaredField("mToken").apply { isAccessible = true }
        val operations = controllerClass.getDeclaredField("mTargetWindowTokens").apply { isAccessible = true }
        val mode = controllerClass.getDeclaredField("mTransitionOp").apply { isAccessible = true }
        val actions = operationClass.getDeclaredField("mActions").apply { isAccessible = true }
        val accept = controllerClass.getDeclaredMethod("accept", windowClass).apply { isAccessible = true }
        module.hook(accept).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val window = chain.args.firstOrNull() ?: return chain.proceed()
                val title = runCatching {
                    val params = attrs.get(window) as? WindowManager.LayoutParams ?: return@runCatching null
                    val name = params.title?.toString()
                    if (params.packageName != "com.android.systemui" || name !in titles) return@runCatching null
                    // Native accept() chooses its fade/seamless action here. A
                    // later Shell snapshot-animation shortcut cannot undo a fade.
                    forceSeamless.setBoolean(window, true)
                    name
                }.onFailure(::reportFailure).getOrNull()
                val result = chain.proceed()
                if (title != null) runCatching {
                    val controller = chain.thisObject ?: return@runCatching
                    val operation = (operations.get(controller) as? Map<*, *>)?.get(token.get(window))
                    val action = operation?.let { actions.getInt(it) }
                    NeXtepLog.info("workspace_panel_rotation",
                        "Native panel rotation title=$title mode=${mode.getInt(controller)} action=$action")
                }.onFailure(::reportFailure)
                return result
            }
        })
    }

    private fun reportFailure(error: Throwable) {
        if (reportedFailure.compareAndSet(false, true)) {
            NeXtepLog.warn("workspace_panel_rotation", "Panel seamless rotation unavailable; using native handling", error)
        }
    }
}
