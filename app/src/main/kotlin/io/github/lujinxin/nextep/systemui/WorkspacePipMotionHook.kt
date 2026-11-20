package io.github.lujinxin.nextep.systemui

import android.view.SurfaceControl
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.FloatingPipLayer
import java.util.concurrent.atomic.AtomicBoolean

/** PiP drag frames commit in Shell without waiting for a system-server traversal. */
internal object WorkspacePipMotionHook {
    @Volatile var layer: FloatingPipLayer? = null
    private val reportedFailure = AtomicBoolean()

    fun install(module: XposedModule, loader: ClassLoader) {
        val helpers = listOf(
            "com.android.wm.shell.pip.PipSurfaceTransactionHelper",
            "com.android.wm.shell.pip2.PipSurfaceTransactionHelper",
        )
        val methods = helpers.mapNotNull { name ->
            try {
                loader.loadClass(name)
            } catch (_: ClassNotFoundException) {
                null
            }
        }.flatMap { type ->
            type.declaredMethods.filter { method ->
                val parameters = method.parameterTypes
                when (method.name) {
                    "setPipTransformations" -> parameters.getOrNull(0) == SurfaceControl::class.java &&
                        parameters.getOrNull(1) == SurfaceControl.Transaction::class.java
                    "round" -> parameters.getOrNull(0) == SurfaceControl.Transaction::class.java &&
                        parameters.getOrNull(1) == SurfaceControl::class.java
                    else -> false
                }
            }
        }
        check(methods.isNotEmpty()) { "No compatible PiP frame helper" }
        val sameSurface = SurfaceControl::class.java.getMethod("isSameSurface", SurfaceControl::class.java)
        val setRelativeLayer = SurfaceControl.Transaction::class.java.getMethod(
            "setRelativeLayer", SurfaceControl::class.java, SurfaceControl::class.java,
            Int::class.javaPrimitiveType,
        )
        fun frameHook(transactionIndex: Int, leashIndex: Int) = object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                val order = layer ?: return result
                if (!SystemUiRuntime.isWorkspaceActive()) return result
                val transaction = chain.args[transactionIndex] as? SurfaceControl.Transaction ?: return result
                val leash = chain.args[leashIndex] as? SurfaceControl ?: return result
                runCatching {
                    if (order.anchor.isValid && order.surface.isValid && leash.isValid &&
                        sameSurface.invoke(leash, order.surface) == true
                    ) {
                        // Keep native geometry and z-order in the same submitted frame.
                        setRelativeLayer.invoke(transaction, leash, order.anchor, order.offset)
                    }
                }.onFailure {
                    if (reportedFailure.compareAndSet(false, true)) {
                        NeXtepLog.warn("workspace_pip_motion", "Unable to order floating PiP frame", it)
                    }
                }
                return result
            }
        }
        val rounding = frameHook(transactionIndex = 0, leashIndex = 1)
        val transformation = frameHook(transactionIndex = 1, leashIndex = 0)
        methods.forEach { method ->
            // ColorOS PiP2 calls setLayer after round() in setPipTransformations().
            // Append the relative order after that final write and before Shell applies the frame.
            val hook = if (method.name == "setPipTransformations") transformation else rounding
            module.hook(method.apply { isAccessible = true }).intercept(hook)
        }
        NeXtepLog.info("workspace_pip_motion", "Installed floating PiP frame layer hook")
    }
}
