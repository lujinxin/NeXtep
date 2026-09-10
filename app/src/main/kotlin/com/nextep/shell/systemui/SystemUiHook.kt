package com.nextep.shell.systemui

import android.app.Application
import android.app.Instrumentation
import com.nextep.shell.logging.NeXtepLog
import com.nextep.shell.xposed.HookGuard
import io.github.libxposed.api.XposedModule
import android.view.MotionEvent
import android.view.ViewGroup
import com.nextep.shell.safety.FeatureGate

object SystemUiHook {
    private data class StartupCandidate(val className: String, val methodName: String)

    private val touchRootCandidates = listOf(
        "com.android.systemui.statusbar.window.StatusBarWindowView",
        "com.android.systemui.shade.NotificationShadeWindowView",
        "com.android.systemui.statusbar.phone.StatusBarWindowView",
        "com.android.systemui.statusbar.phone.PhoneStatusBarView",
    )

    private val startupCandidates = listOf(
        StartupCandidate("com.android.systemui.SystemUIApplication", "onCreate"),
    )

    fun install(module: XposedModule, classLoader: ClassLoader) {
        HookGuard.run("system_dialog_layout") {
            Class.forName("android.view.WindowManagerGlobal").declaredMethods
                .filter { it.name in setOf("addView", "updateViewLayout") &&
                    it.parameterTypes.firstOrNull() == android.view.View::class.java }
                .forEach { method ->
                    method.isAccessible = true
                    module.hook(method).intercept(object : io.github.libxposed.api.XposedInterface.Hooker {
                        override fun intercept(chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
                            val view = chain.args.firstOrNull() as? android.view.View
                            val params = chain.args.getOrNull(1) as? android.view.WindowManager.LayoutParams
                            if (view != null && params != null) {
                                HookGuard.run("system_dialog_layout_apply") {
                                    SystemDialogLayoutController.beforeLayout(view, params)
                                }
                            }
                            return chain.proceed()
                        }
                    })
                }
        }
        HookGuard.run("systemui_framework_startup") {
            val method = Instrumentation::class.java.getDeclaredMethod(
                "callApplicationOnCreate",
                Application::class.java,
            )
            method.isAccessible = true
            module.hook(method).intercept(SystemUiApplicationHooker())
            NeXtepLog.info(
                "systemui_framework_startup",
                "Installed framework Application bootstrap fallback",
            )
        }

        if (FeatureGate.TOP_RIGHT_STATUS_BAR_GESTURE.defaultEnabled) {
            HookGuard.run("systemui_root_touch") {
                val method = ViewGroup::class.java.getDeclaredMethod(
                    "dispatchTouchEvent",
                    MotionEvent::class.java,
                )
                method.isAccessible = true
                module.hook(method).intercept(SystemUiRootTouchHooker())
                NeXtepLog.info("systemui_root_touch", "Installed public root touch observer")
            }
            touchRootCandidates.forEach { className ->
                HookGuard.run("systemui_root_touch_override") {
                    val rootClass = classLoader.loadClass(className)
                    val method = rootClass.getDeclaredMethod(
                        "dispatchTouchEvent",
                        MotionEvent::class.java,
                    )
                    method.isAccessible = true
                    module.hook(method).intercept(SystemUiRootTouchHooker())
                    NeXtepLog.info(
                        "systemui_root_touch_override",
                        "Installed override observer on $className",
                    )
                }
            }
        }

        var installed = false
        for (candidate in startupCandidates) {
            val success = HookGuard.run("systemui_startup_candidate") {
                val startupClass = classLoader.loadClass(candidate.className)
                val method = startupClass.getDeclaredMethod(candidate.methodName)
                method.isAccessible = true
                module.hook(method).intercept(SystemUiStartupHooker())
                NeXtepLog.info(
                    "systemui_startup_candidate",
                    "Installed ${candidate.className}#${candidate.methodName}",
                )
            }
            if (success) {
                installed = true
                break
            }
        }
        if (!installed) {
            NeXtepLog.warn(
                "systemui_startup_candidate",
                "No startup candidate installed; device artifact analysis is required",
            )
        }
    }
}
