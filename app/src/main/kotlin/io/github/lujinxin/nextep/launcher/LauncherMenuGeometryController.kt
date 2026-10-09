package io.github.lujinxin.nextep.launcher

import android.provider.Settings
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.NeXtepLog

/** COUI's detached menu uses the display origin, not HOME's translated decor origin. */
internal object LauncherMenuGeometryController {
    private val menuRoot = ThreadLocal<View?>()

    fun install(module: XposedModule, loader: ClassLoader) {
        val locate = loader.loadClass("com.coui.appcompat.poplist.i").getDeclaredMethod(
            "j", View::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        module.hook(locate).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val anchor = chain.getArg(0) as? View ?: return chain.proceed()
                if (!LauncherTransformController.isActive() ||
                    !Settings.canDrawOverlays(anchor.context) ||
                    !LauncherPackageResolver.isCurrentHome(anchor.context)) return chain.proceed()
                val previous = menuRoot.get()
                menuRoot.set(anchor.rootView)
                return try {
                    chain.proceed()
                } finally {
                    if (previous == null) menuRoot.remove() else menuRoot.set(previous)
                }
            }
        })
        module.hook(View::class.java.getDeclaredMethod("getLocationOnScreen", IntArray::class.java))
            .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    if (menuRoot.get() === chain.thisObject) {
                        val location = chain.getArg(0) as IntArray
                        // COUI subtracts this origin from the physical visible display frame.
                        // Subtracting HOME's workspace translation invents a bottom inset,
                        // squeezing folder shortcut rows into a fraction of their height.
                        location[0] = 0
                        location[1] = 0
                    }
                    return result
                }
            })
        NeXtepLog.info("launcher_menu_geometry", "Installed detached shortcut menu display origin")
    }
}
