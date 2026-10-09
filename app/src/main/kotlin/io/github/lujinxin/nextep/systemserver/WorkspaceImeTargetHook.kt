package io.github.lujinxin.nextep.systemserver

import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge

/** A parked side task must not replace the foreground app's input-method target. */
internal object WorkspaceImeTargetHook {
    private data class Move(val window: Any, val destination: Any)
    private val parkedWindow = ThreadLocal<Move?>()

    fun install(module: XposedModule, loader: ClassLoader) {
        val windowClass = loader.loadClass("com.android.server.wm.WindowState")
        val displayClass = loader.loadClass("com.android.server.wm.DisplayContent")
        val inputTargetClass = loader.loadClass("com.android.server.wm.InputTarget")
        module.hook(windowClass.getDeclaredMethod("onDisplayChanged", displayClass))
            .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val window = chain.thisObject ?: return chain.proceed()
                    val service = field(window, "mWmService") ?: return chain.proceed()
                    val context = field(service, "mContext") as? Context ?: return chain.proceed()
                    // A user promoting a slot to main must retain native IME handoff.
                    // Exit publishes the inactive gate before parking the side tasks.
                    if (SystemServerWorkspaceBridge.isWorkspaceActive(context)) return chain.proceed()
                    val destination = chain.getArg(0) ?: return chain.proceed()
                    val source = field(window, "mDisplayContent") ?: return chain.proceed()
                    val info = call(source, "getDisplayInfo") ?: return chain.proceed()
                    if ((field(info, "name") as? String)?.startsWith("NeXtep-slot-") != true ||
                        call(destination, "getDisplayId") != 0) return chain.proceed()
                    val focused = field(destination, "mCurrentFocus")
                    if (focused == null || focused === window) return chain.proceed()
                    val previous = parkedWindow.get()
                    parkedWindow.set(Move(window, destination))
                    return try {
                        chain.proceed()
                    } finally {
                        if (previous == null) parkedWindow.remove() else parkedWindow.set(previous)
                    }
                }
            })
        module.hook(displayClass.getDeclaredMethod("updateImeInputAndControlTarget", inputTargetClass))
            .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val move = parkedWindow.get()
                    if (move != null && chain.thisObject === move.destination &&
                        chain.getArg(0) === move.window) {
                        // WindowState transfers its old display's IME target before updating
                        // its own display. That is valid for foreground moves, but a side
                        // display's focused window is only being parked behind the main app.
                        // Keep native cleanup on the old display and all subsequent focus
                        // updates; suppress only this stale target transfer to display 0.
                        return null
                    }
                    return chain.proceed()
                }
            })
        NeXtepLog.info("workspace_ime_target", "Installed foreground IME preservation during slot parking")
    }

    private fun field(target: Any, name: String): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()?.apply { isAccessible = true }?.get(target)

    private fun call(target: Any, name: String): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterCount == 0 }
            ?.apply { isAccessible = true }?.invoke(target)
}
