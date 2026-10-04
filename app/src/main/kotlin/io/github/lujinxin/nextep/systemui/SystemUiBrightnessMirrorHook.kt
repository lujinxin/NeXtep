package io.github.lujinxin.nextep.systemui

import android.view.View
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.xposed.HookGuard
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

object SystemUiBrightnessMirrorHook {
    private val positioningDepth = ThreadLocal<Int>()
    private val positioningClasses = listOf(
        "com.android.systemui.statusbar.policy.BrightnessMirrorController",
        "com.android.systemui.qs.brightness.BrightnessMirrorController",
        "com.oplus.systemui.statusbar.policy.OplusBrightnessMirrorController",
        "com.oplus.systemui.statusbar.policy.OplusQsBrightnessSliderMirrorController",
        "com.oplus.systemui.statusbar.policy.OplusQsBrightnessSliderMirrorController\$originPreDrawListener\$1",
    )

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val coordinatesInstalled = HookGuard.run("brightness_mirror_coordinates") {
            val method = View::class.java.getDeclaredMethod("getLocationInWindow", IntArray::class.java)
            method.isAccessible = true
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    if ((positioningDepth.get() ?: 0) > 0) {
                        val view = chain.thisObject as? View
                        val location = chain.getArg(0) as? IntArray
                        if (view != null && location != null) {
                            HookGuard.run("brightness_mirror_coordinates_apply") {
                                SystemUiRootTransformController.mapWindowLocationToContent(view, location)
                            }
                        }
                    }
                    return result
                }
            })
        }
        if (!coordinatesInstalled) return

        positioningClasses.forEach { className ->
            val controllerClass = runCatching { classLoader.loadClass(className) }.getOrNull()
                ?: return@forEach
            HookGuard.run("brightness_mirror_position") {
                controllerClass.declaredMethods.filter { method ->
                    (method.name in setOf("setLocationAndSize", "updateOriginCacheAndMirror") &&
                        method.parameterTypes.contentEquals(arrayOf(View::class.java))) ||
                        (method.name in setOf("setMirrorTranslationAndSize", "onPreDraw") &&
                            method.parameterCount == 0)
                }.forEach { method ->
                    method.isAccessible = true
                    // ROM-compiled positioning code can inline View.getLocationInWindow and
                    // bypass its hook. Deoptimize only these callers before hooking them.
                    val deoptimized = module.deoptimize(method)
                    module.hook(method).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            // The OEM mirror uses window-coordinate differences as local translations.
                            // Undo the shade root's transform for both the source and mirror queries,
                            // including the OEM's pre-draw source cache refresh and later relayout.
                            val depth = positioningDepth.get() ?: 0
                            positioningDepth.set(depth + 1)
                            return try {
                                chain.proceed()
                            } finally {
                                if (depth == 0) positioningDepth.remove() else positioningDepth.set(depth)
                            }
                        }
                    })
                    NeXtepLog.info(
                        "brightness_mirror_position",
                        "Installed $className#${method.name} deoptimized=$deoptimized",
                    )
                }
            }
        }
    }
}
