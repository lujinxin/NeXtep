package com.nextep.shell.systemserver

import android.content.res.Configuration
import android.util.Log
import com.nextep.shell.logging.NeXtepLog
import com.nextep.shell.safety.FeatureGate
import com.nextep.shell.xposed.HookGuard
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

object SystemServerHook {
    private val installed = AtomicBoolean(false)
    private val aspectClassCandidates = listOf(
        "com.android.server.wm.AppCompatAspectRatioOverrides",
        "com.android.server.wm.LetterboxUiController",
    )
    private val sizeCompatClassCandidates = listOf(
        "com.android.server.wm.ActivityRecord",
        "com.android.server.wm.AppCompatSizeCompatModePolicy",
    )

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        val aspectInstalled = installAspectHook(module, classLoader)
        val sizeCompatInstalled = installSizeCompatHook(module, classLoader)
        HookGuard.run("slot_display_config") {
            val displayClass = classLoader.loadClass("com.android.server.wm.DisplayContent")
            val method = displayClass.declaredMethods.single {
                it.name == "computeScreenConfiguration" &&
                    it.parameterTypes.contentEquals(arrayOf(Configuration::class.java))
            }
            method.isAccessible = true
            module.hook(method).intercept(SlotDisplayConfigurationHooker())
            NeXtepLog.info("slot_display_config", "Installed package-independent slot capability matching")
        }
        if (!aspectInstalled && !sizeCompatInstalled) {
            // Nothing was installed; keep the guard open for a potential future retry.
            installed.set(false)
            module.log(Log.ERROR, "NeXtep", "system_server hooks failed open: no compatible target found")
        } else {
            module.log(
                Log.INFO,
                "NeXtep",
                "system_server hooks installed: fixed-orientation aspect=$aspectInstalled size-compat insets=$sizeCompatInstalled",
            )
        }
    }

    private fun installAspectHook(module: XposedModule, classLoader: ClassLoader): Boolean {
        if (!FeatureGate.FIXED_ORIENTATION_LETTERBOX.defaultEnabled) return true
        return HookGuard.run("fixed_orientation_aspect") {
            val targetClass = aspectClassCandidates.firstNotNullOfOrNull { name ->
                runCatching { classLoader.loadClass(name) }.getOrNull()
            } ?: error("No fixed-orientation aspect policy class")
            val method = generateSequence(targetClass as Class<*>?) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { candidate ->
                    candidate.name == "getFixedOrientationLetterboxAspectRatio" &&
                        candidate.parameterTypes.size == 1 &&
                        candidate.parameterTypes[0] == Configuration::class.java
                } ?: error("No compatible getFixedOrientationLetterboxAspectRatio method")
            method.isAccessible = true
            module.hook(method).intercept(FixedOrientationAspectRatioHooker())
            NeXtepLog.info(
                "fixed_orientation_aspect",
                "Installed ${targetClass.name}#${method.name}",
            )
        }
    }

    private fun installSizeCompatHook(module: XposedModule, classLoader: ClassLoader): Boolean {
        if (!FeatureGate.SIZE_COMPAT_DISPLAY_INSETS.defaultEnabled) return true
        return HookGuard.run("size_compat_display_insets") {
            val methods = sizeCompatClassCandidates.mapNotNull { name ->
                runCatching { classLoader.loadClass(name) }.getOrNull()
            }.flatMap { targetClass ->
                generateSequence(targetClass as Class<*>?) { it.superclass }
                    .flatMap { it.declaredMethods.asSequence() }
                    .filter { method ->
                        method.name == "shouldCreateAppCompatDisplayInsets" &&
                            method.parameterTypes.isEmpty() &&
                            method.returnType == Boolean::class.javaPrimitiveType
                    }
                    .map { targetClass to it }
                    .toList()
            }
            if (methods.isEmpty()) error("No compatible size-compat display-insets method")
            methods.forEach { (targetClass, method) ->
                method.isAccessible = true
                module.hook(method).intercept(SizeCompatDisplayInsetsHooker())
                NeXtepLog.info(
                    "size_compat_display_insets",
                    "Installed ${targetClass.name}#${method.name}",
                )
            }
        }
    }
}
