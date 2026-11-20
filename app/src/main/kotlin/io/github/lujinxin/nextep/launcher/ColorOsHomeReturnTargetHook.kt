package io.github.lujinxin.nextep.launcher

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import java.lang.reflect.Method
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.xposed.HookGuard

/** Convert ColorOS return-to-launcher targets to the visible workspace coordinates. */
internal object ColorOsHomeReturnTargetHook {
    private class IconTarget(val view: View, val anchor: View) {
        var bounds: RectF? = null
    }

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val folderOwner = runCatching {
            classLoader.loadClass("com.android.launcher3.folder.big.BigFolderItemIcon")
                .getMethod("getParentFolderIcon")
        }.getOrNull()
        HookGuard.run("launcher_workspace_swipe_home_target") { installSwipeHome(module, classLoader, folderOwner) }
        HookGuard.run("launcher_workspace_app_close_target") { installAppClose(module, classLoader, folderOwner) }
    }

    private fun installSwipeHome(module: XposedModule, classLoader: ClassLoader, folderOwner: Method?) {
        // OplusLauncher 17.5.4 uses an obfuscated HOME factory. Verify it against
        // the named runner before installing a hook for this variant.
        val factory = try {
            classLoader.loadClass("hi7")
        } catch (_: ClassNotFoundException) {
            return
        }
        val runnerParams = classLoader.loadClass(
            "com.android.launcher3.anim.engine.factory.SwipeToHomeAnimRunner\$Params",
        )
        val factoryType = runnerParams.getDeclaredMethod("getHomeAnimationFactory").returnType
        if (!factoryType.isAssignableFrom(factory)) return

        val targetBounds = factory.getDeclaredMethod("m").apply {
            if (returnType != RectF::class.java) return
            isAccessible = true
        }
        val targetView = factory.getDeclaredMethod("n").apply {
            if (returnType != View::class.java) return
            isAccessible = true
        }
        val dragLayerType = classLoader.loadClass("com.android.launcher3.views.BaseDragLayer")

        module.hook(targetBounds).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val bounds = chain.proceed() as? RectF ?: return null
                if (bounds.isEmpty || !LauncherTransformController.canUseWorkspaceAnimations()) return bounds

                return runCatching {
                    val source = targetView.invoke(chain.thisObject) as? View ?: return@runCatching null
                    val anchor = iconAnchor(source, folderOwner) ?: return@runCatching null
                    toDisplayBounds(anchor, bounds, dragLayerType)
                }.getOrNull() ?: bounds
            }
        })
        NeXtepLog.info("launcher_workspace_swipe_home_target", "Native HOME spring target hook installed")
    }

    private fun installAppClose(module: XposedModule, classLoader: ClassLoader, folderOwner: Method?) {
        val helper = try {
            classLoader.loadClass("com.android.launcher3.anim.OplusLauncherAppTransitionHelper")
        } catch (_: ClassNotFoundException) {
            return
        }
        // Both predictive Back and the regular close animator use this helper.
        val close = helper.declaredMethods.singleOrNull {
            it.name == "startAppCloseWindowAnim" && it.parameterTypes.size == 6 &&
                it.parameterTypes[1] == RectF::class.java && it.parameterTypes[2] == View::class.java &&
                it.returnType.name == "com.android.quickstep.util.animation.a"
        }?.apply { isAccessible = true } ?: return
        val launcherType = classLoader.loadClass("com.android.launcher3.Launcher")
        val dragLayerType = classLoader.loadClass("com.android.launcher3.views.BaseDragLayer")
        val floatingIcon = classLoader.loadClass("com.android.launcher3.views.FloatingIconView")
        val getBounds = floatingIcon.getDeclaredMethod(
            "getLocationBoundsForView", launcherType, View::class.java,
            Boolean::class.javaPrimitiveType, RectF::class.java, Rect::class.java,
        ).apply { isAccessible = true }

        val booleanType = Boolean::class.javaPrimitiveType
        val floatingIconWrapper = helper.getDeclaredMethod(
            "getFloatingIconViewWrapper", booleanType, View::class.java, booleanType,
            RectF::class.java, booleanType, booleanType,
        ).apply { isAccessible = true }

        // Capture the rectangle owned by the close helper. Other bounds reads
        // still supply launcher-local coordinates for ordinary view layout.
        val activeTarget = ThreadLocal<IconTarget>()
        module.hook(getBounds).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val target = activeTarget.get() ?: return chain.proceed()
                val bounds = chain.getArg(3) as? RectF ?: return chain.proceed()
                if (target.bounds != null || chain.getArg(1) !== target.view) return chain.proceed()
                target.bounds = bounds
                val result = chain.proceed()
                updateIconTarget(target.anchor, bounds, dragLayerType)
                return result
            }
        })
        module.hook(floatingIconWrapper).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val target = activeTarget.get() ?: return chain.proceed()
                val bounds = target.bounds ?: return chain.proceed()
                if (chain.getArg(1) !== target.view || chain.getArg(3) !== bounds) return chain.proceed()
                val previous = RectF(bounds)
                val result = chain.proceed()
                // Icon creation can refill the same rectangle with local coordinates.
                // Convert only a rewritten rectangle before the helper starts the spring.
                if (bounds != previous) updateIconTarget(target.anchor, bounds, dragLayerType)
                return result
            }
        })
        fun hookTransition(method: Method, viewIndex: Int, previewsOnly: Boolean) {
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val source = chain.getArg(viewIndex) as? View ?: return chain.proceed()
                    val anchor = iconAnchor(source, folderOwner) ?: return chain.proceed()
                    if ((previewsOnly && anchor === source) ||
                        !LauncherTransformController.canUseWorkspaceAnimations(anchor.rootView)
                    ) return chain.proceed()

                    val previous = activeTarget.get()
                    activeTarget.set(IconTarget(source, anchor))
                    return try {
                        chain.proceed()
                    } finally {
                        if (previous == null) activeTarget.remove() else activeTarget.set(previous)
                    }
                }
            })
        }
        hookTransition(close, 2, previewsOnly = false)
        helper.declaredMethods.singleOrNull {
            it.name == "startAppLaunchWindowAnim" && it.parameterTypes.size == 8 &&
                it.parameterTypes[0] == View::class.java && it.returnType == close.returnType
        }?.let { hookTransition(it, 0, previewsOnly = true) }
        NeXtepLog.info("launcher_workspace_app_close_target", "Native icon target hooks installed")
    }

    // A large-folder preview item can be a detached proxy. ColorOS resolves
    // its indexed slot against the attached folder when computing native bounds.
    private fun iconAnchor(source: View, folderOwner: Method?): View? {
        if (folderOwner == null || !folderOwner.declaringClass.isInstance(source)) return source
        return runCatching { folderOwner.invoke(source) as? View }.getOrNull()
    }

    private fun updateIconTarget(source: View, bounds: RectF, dragLayerType: Class<*>) {
        runCatching { toDisplayBounds(source, bounds, dragLayerType) }
            .getOrNull()?.let { bounds.set(it) }
    }

    private fun toDisplayBounds(source: View, bounds: RectF, dragLayerType: Class<*>): RectF? {
        val decor = source.rootView
        if (bounds.isEmpty || !source.isAttachedToWindow ||
            !LauncherTransformController.canUseWorkspaceAnimations(decor)
        ) return null

        // ColorOS has already resolved the icon (including a folder preview slot)
        // in the launcher's outer drag layer. Keep that native rectangle intact.
        var dragLayer: View? = null
        var ancestor = source.parent
        while (ancestor is View && ancestor !== decor) {
            if (dragLayerType.isInstance(ancestor)) dragLayer = ancestor
            ancestor = ancestor.parent
        }
        var layer = dragLayer ?: return null
        val target = RectF(bounds)
        while (layer !== decor) {
            val parent = layer.parent as? View ?: return null
            target.offset((layer.left - parent.scrollX).toFloat(), (layer.top - parent.scrollY).toFloat())
            layer = parent
        }
        // The drag layer itself is scaled around a pivot during return. Sampling
        // its live matrix bakes that intermediate scale and pivot into the spring's
        // endpoint. Only the workspace transform on the decor survives the handoff.
        val toDisplay = Matrix()
        decor.transformMatrixToGlobal(toDisplay)
        return target.takeIf { toDisplay.mapRect(it) && !it.isEmpty }
    }
}
