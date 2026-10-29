package io.github.lujinxin.nextep.launcher

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.KeyguardManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.Display
import android.view.WindowManager
import android.graphics.Matrix
import android.graphics.Rect
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.systemui.SystemDialogLayoutController
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import io.github.lujinxin.nextep.workspace.WorkspaceTransitionSpec
import java.lang.ref.WeakReference
import java.util.WeakHashMap

object LauncherTransformController {
    private data class OriginalTransform(
        val pivotX: Float,
        val pivotY: Float,
        val scaleX: Float,
        val scaleY: Float,
        val translationX: Float,
        val translationY: Float,
        val animationMatrix: Matrix?,
        val clipBounds: Rect?,
        val screenX: Int,
        val screenY: Int,
    )

    private var decorReference = WeakReference<View>(null)
    private var original: OriginalTransform? = null
    private val assistantRoots = WeakHashMap<View, OriginalTransform>()
    private val assistantTransforms = WeakHashMap<View, TouchCoordinateMapper.Transform>()
    private var appliedTransform: TouchCoordinateMapper.Transform? = null
    private val positionAnimators = WeakHashMap<View, ValueAnimator>()
    private val positionTargets = WeakHashMap<View, TouchCoordinateMapper.Transform>()
    private data class AnimationViewport(val display: Display, val rotation: Int)
    @Volatile private var animationViewport: AnimationViewport? = null
    // ColorOS also reads the workspace gate from its launcher animation thread.
    @Volatile
    private var active = false
    private var applyGeneration = 0
    private var entryAnimationUntil = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val layoutListener = View.OnLayoutChangeListener { view, left, top, right, bottom,
        oldLeft, oldTop, oldRight, oldBottom ->
        if ((right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) &&
            (active || positionAnimators.containsKey(view))
        ) {
            // A camera can rotate display 0 while HOME retains its portrait window.
            // Do not continue an animation whose endpoints belonged to the old layout.
            releaseViewportTransform(view)
            entryAnimationUntil = 0L
            if (active) scheduleApply()
        }
    }

    fun attach(view: View) {
        ensureMainThread()
        if (!LauncherPackageResolver.isWorkspaceSurface(view.context)) return

        val decor = view.rootView ?: view
        if (LauncherPackageResolver.isAssistantScreen(decor.context) && !isAssistantPanel(decor)) {
            // Assistant Activities (profile/settings) are ordinary tasks. SystemUI fits
            // their task leash, including native input; another decor matrix doubles
            // both the scale and offset. Only the separate assistant panel needs this.
            assistantRoots.remove(decor)?.let { restore(decor, it) }
            decor.removeOnLayoutChangeListener(layoutListener)
            assistantTransforms.remove(decor)
            positionAnimators.remove(decor)?.cancel()
            positionTargets.remove(decor)
            return
        }
        if (decor.width <= 0 || decor.height <= 0) {
            decor.post { attach(decor) }
            return
        }

        if (LauncherPackageResolver.isAssistantScreen(decor.context)) {
            assistantRoots.getOrPut(decor) {
                decor.addOnLayoutChangeListener(layoutListener)
                captureOriginal(decor)
            }
        } else if (decorReference.get() !== decor) {
            decorReference.get()?.removeOnLayoutChangeListener(layoutListener)
            restoreCurrentDecor()
            decorReference = WeakReference(decor)
            original = captureOriginal(decor)
            decor.addOnLayoutChangeListener(layoutListener)
        }

        if (active) scheduleApply()
    }

    fun attach(activity: android.app.Activity) {
        activity.window?.decorView?.let(::attach)
    }

    fun prepareForConfigurationChange(activity: android.app.Activity) {
        ensureMainThread()
        val decor = decorReference.get() ?: return
        if (!active || activity.window?.peekDecorView() !== decor) return
        // Restore before Launcher rebuilds its device profile for camera rotation.
        // Reapply on the main queue after the complete OEM configuration callback.
        releaseViewportTransform(decor)
        entryAnimationUntil = 0L
        scheduleApply()
    }

    fun attachExistingAssistantWindows(context: android.content.Context) {
        if (!LauncherPackageResolver.isAssistantScreen(context)) return
        runCatching {
            val managerClass = Class.forName("android.view.WindowManagerGlobal")
            val manager = managerClass.getDeclaredMethod("getInstance").apply {
                isAccessible = true
            }.invoke(null)
            val views = managerClass.getDeclaredField("mViews").apply {
                isAccessible = true
            }.get(manager) as? Collection<*> ?: emptyList<Any>()
            val roots = views.filterIsInstance<View>()
            NeXtepLog.info("assistant_root", "Discovered existing roots=${roots.size}")
            roots.forEach(::attach)
        }.onFailure { error ->
            NeXtepLog.warn("assistant_root", "Unable to enumerate existing roots", error)
        }
    }

    fun toggle(): Boolean {
        return setActive(!active)
    }

    fun setActive(requestedActive: Boolean): Boolean {
        ensureMainThread()
        val changed = active != requestedActive
        // Duplicate inactive broadcasts must not cut an exit animation short.
        if (!changed && !active && positionAnimators.isNotEmpty()) {
            val root = decorReference.get() ?: assistantRoots.keys.firstOrNull()
            if (root != null && canAnimate(root)) return true
        }
        active = requestedActive
        if (active && changed) entryAnimationUntil = SystemClock.uptimeMillis() + WorkspaceTransitionSpec.DURATION_MS
        if (!active) {
            LauncherShortcutMenuController.dismissMenus()
            SystemDialogLayoutController.setGeometry(null)
        }
        applyGeneration += 1
        val decor = decorReference.get() ?: assistantRoots.keys.firstOrNull()
        if (decor == null) {
            NeXtepLog.info(
                "launcher_transform",
                "active=$active pending=true; waiting for workspace surface DecorView attach",
            )
            return true
        }
        if (active) scheduleApply() else restoreCurrentDecor(animate = changed && canAnimate(decor))
        NeXtepLog.info("launcher_transform", "active=$active decor=${decor.javaClass.name}")
        return true
    }

    fun isActive(): Boolean = active

    /** Called on ColorOS's animation thread as well as the launcher main thread. */
    fun canUseWorkspaceAnimations(): Boolean {
        val viewport = animationViewport ?: return false
        return active && runCatching {
            viewport.display.isValid && viewport.display.rotation == viewport.rotation
        }.getOrDefault(false)
    }

    fun mapToContent(event: MotionEvent?): TouchCoordinateMapper.Transform? {
        val transform = appliedTransform
        return transform?.takeIf { TouchCoordinateMapper.toContent(event, it) }
    }

    fun mapToContent(view: View, event: MotionEvent?): TouchCoordinateMapper.Transform? {
        val transform = assistantTransforms[view.rootView ?: view]
        return transform?.takeIf { TouchCoordinateMapper.toContent(event, it) }
    }

    fun mapToScreen(event: MotionEvent?, transform: TouchCoordinateMapper.Transform?) {
        TouchCoordinateMapper.toScreen(event, transform)
    }

    private fun applyTo(decor: View) {
        if (LauncherPackageResolver.isAssistantScreen(decor.context) && !isAssistantPanel(decor)) return
        if (!decor.isAttachedToWindow) {
            releaseViewportTransform(decor)
            return
        }
        val isAssistant = LauncherPackageResolver.isAssistantScreen(decor.context)
        val display = decor.display
        val displayMetrics = decor.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val geometry = SystemServerWorkspaceBridge.activeGeometry(decor.context) ?: WorkspaceGeometry.forDisplay(
            screenWidth,
            screenHeight,
            SystemServerWorkspaceBridge.sidebarSide(decor.context),
            density = displayMetrics.density,
            displayRotation = decor.display?.rotation ?: 0,
        )
        if (display == null || display.displayId != Display.DEFAULT_DISPLAY ||
            !LauncherAnimationPolicy.matchesViewport(
                geometry.displayRotation, display.rotation, geometry.screenWidth, geometry.screenHeight,
                if (isAssistant) geometry.screenWidth else decor.width,
                if (isAssistant) geometry.screenHeight else decor.height,
            )
        ) {
            // SystemUI publication, the physical display and HOME's layout update
            // independently. Leave native coordinates in place until they agree.
            releaseViewportTransform(decor)
            entryAnimationUntil = 0L
            return
        }
        if (!isAssistant) {
            if (appliedTransform == null && !positionAnimators.containsKey(decor)) {
                // The native callback may have replaced its pivot/clip while we
                // were released. Future restores must use that updated baseline.
                original = captureOriginal(decor)
            }
            SystemDialogLayoutController.setGeometry(geometry)
            animationViewport = AnimationViewport(display, geometry.displayRotation)
        }
        val saved = assistantRoots[decor]
        val sourceBounds = if (isAssistant && saved != null) {
            // Use SystemUI's recorded navigation mode, including zero padding in
            // gesture mode. A page transition must not sample different live insets.
            val insets = geometry.navigationInsets
            Rect(insets.left, insets.top,
                decor.width - insets.right, decor.height - insets.bottom)
        } else Rect(geometry.availableLeft, geometry.availableTop,
            geometry.availableRight, geometry.availableBottom)
        if (sourceBounds.width() <= 0 || sourceBounds.height() <= 0) return
        val transform = if (isAssistant && saved != null) {
            val scale = minOf(geometry.contentWidth.toFloat() / sourceBounds.width(),
                geometry.contentHeight.toFloat() / sourceBounds.height())
            TouchCoordinateMapper.Transform(
                scaleX = scale,
                scaleY = scale,
                // The assistant window itself is horizontally translated by Launcher while
                // swiping between pages. Its animation matrix must stay window-local; folding
                // the transient screen location (often -screenWidth while off-screen) into this
                // translation pushes the scaled page outside the workspace.
                translationX = geometry.contentLeft - sourceBounds.left * scale,
                translationY = geometry.contentTop - sourceBounds.top * scale,
            )
        } else {
            TouchCoordinateMapper.Transform(
                scaleX = geometry.contentScale,
                scaleY = geometry.contentScale,
                translationX = geometry.contentTranslationX,
                translationY = geometry.contentTranslationY,
            )
        }

        val current = if (isAssistant) assistantTransforms[decor] else appliedTransform
        // Delayed reapplication must not snap or restart an in-flight side switch.
        if (positionAnimators[decor]?.isRunning == true && positionTargets[decor] == transform) return
        val savedTransform = if (isAssistant) saved else original
        val entering = current == null && SystemClock.uptimeMillis() <= entryAnimationUntil
        if ((entering || (current != null && current != transform)) && canAnimate(decor)) {
            animateTransform(decor, current ?: nativeTransform(savedTransform, isAssistant),
                transform, isAssistant, sourceBounds)
        } else {
            cancelPositionAnimation(decor)
            decor.clipBounds = sourceBounds
            writeTransform(decor, transform, isAssistant)
        }
    }

    private fun nativeTransform(saved: OriginalTransform?, isAssistant: Boolean): TouchCoordinateMapper.Transform {
        if (saved == null) return TouchCoordinateMapper.Transform(1f, 1f, 0f, 0f)
        if (isAssistant) {
            val values = FloatArray(9)
            (saved.animationMatrix ?: Matrix()).getValues(values)
            return TouchCoordinateMapper.Transform(values[0], values[4], values[2], values[5])
        }
        return TouchCoordinateMapper.Transform(saved.scaleX, saved.scaleY,
            saved.translationX + saved.pivotX * (1f - saved.scaleX),
            saved.translationY + saved.pivotY * (1f - saved.scaleY))
    }

    private fun animateTransform(
        decor: View,
        from: TouchCoordinateMapper.Transform,
        to: TouchCoordinateMapper.Transform,
        isAssistant: Boolean,
        targetClip: Rect?,
        onFinished: () -> Unit = {},
    ) {
        cancelPositionAnimation(decor)
        positionTargets[decor] = to
        val fullBounds = Rect(0, 0, decor.width, decor.height)
        val startClip = decor.clipBounds?.let(::Rect) ?: fullBounds
        val endClip = targetClip ?: fullBounds
        val animator = ValueAnimator.ofFloat(0f, 1f)
        val startRotation = decor.display?.rotation
        animator.duration = WorkspaceTransitionSpec.DURATION_MS
        animator.interpolator = WorkspaceTransitionSpec.interpolator
        animator.addUpdateListener {
            if (!decor.isAttachedToWindow || decor.display?.rotation != startRotation) {
                releaseViewportTransform(decor)
                entryAnimationUntil = 0L
                if (active) scheduleApply()
                return@addUpdateListener
            }
            val progress = it.animatedValue as Float
            fun lerp(start: Float, end: Float) = start + (end - start) * progress
            decor.clipBounds = Rect(
                lerp(startClip.left.toFloat(), endClip.left.toFloat()).toInt(),
                lerp(startClip.top.toFloat(), endClip.top.toFloat()).toInt(),
                lerp(startClip.right.toFloat(), endClip.right.toFloat()).toInt(),
                lerp(startClip.bottom.toFloat(), endClip.bottom.toFloat()).toInt(),
            )
            writeTransform(decor, TouchCoordinateMapper.Transform(
                lerp(from.scaleX, to.scaleX), lerp(from.scaleY, to.scaleY),
                lerp(from.translationX, to.translationX), lerp(from.translationY, to.translationY),
            ), isAssistant)
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (positionAnimators[decor] !== animator) return
                positionAnimators.remove(decor)
                positionTargets.remove(decor)
                decor.clipBounds = targetClip
                writeTransform(decor, to, isAssistant)
                onFinished()
            }
        })
        positionAnimators[decor] = animator
        animator.start()
    }

    private fun cancelPositionAnimation(decor: View) {
        positionTargets.remove(decor)
        positionAnimators.remove(decor)?.cancel()
    }

    private fun releaseViewportTransform(decor: View) {
        val animating = positionAnimators.containsKey(decor)
        cancelPositionAnimation(decor)
        if (LauncherPackageResolver.isAssistantScreen(decor.context)) {
            val ownedTransform = assistantTransforms.remove(decor)
            if (ownedTransform != null || animating) {
                assistantRoots[decor]?.let { restore(decor, it) }
            }
            if (!active) {
                assistantRoots.remove(decor)
                decor.removeOnLayoutChangeListener(layoutListener)
            }
        } else {
            animationViewport = null
            if (appliedTransform != null || animating) original?.let { restore(decor, it) }
            appliedTransform = null
            SystemDialogLayoutController.setGeometry(null)
        }
    }

    private fun canAnimate(view: View): Boolean = runCatching {
        view.isAttachedToWindow && ValueAnimator.areAnimatorsEnabled() &&
            view.context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
            view.context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
    }.getOrDefault(false)

    private fun writeTransform(
        decor: View,
        transform: TouchCoordinateMapper.Transform,
        isAssistant: Boolean,
    ) {
        if (isAssistant) {
            decor.animationMatrix = Matrix().apply {
                setValues(
                    floatArrayOf(
                        transform.scaleX, 0f, transform.translationX,
                        0f, transform.scaleY, transform.translationY,
                        0f, 0f, 1f,
                    ),
                )
            }
            assistantTransforms[decor] = transform
        } else {
            decor.pivotX = 0f
            decor.pivotY = 0f
            decor.scaleX = transform.scaleX
            decor.scaleY = transform.scaleY
            decor.translationX = transform.translationX
            decor.translationY = transform.translationY
        }
        if (!isAssistant) appliedTransform = transform
    }

    private fun scheduleApply() {
        val generation = ++applyGeneration
        REAPPLY_DELAYS_MS.forEach { delay ->
            handler.postDelayed({
                if (!active || generation != applyGeneration) return@postDelayed
                decorReference.get()?.let(::applyTo)
                assistantRoots.keys.toList().forEach { root ->
                    if (root.parent != null) applyTo(root)
                }
            }, delay)
        }
    }

    private fun restoreCurrentDecor(animate: Boolean = false) {
        animationViewport = null
        val decor = decorReference.get()
        val saved = original
        if (decor != null && saved != null &&
            (appliedTransform != null || positionAnimators.containsKey(decor))
        ) {
            val current = appliedTransform
            if (animate && current != null && canAnimate(decor)) {
                animateTransform(decor, current, nativeTransform(saved, false), false, saved.clipBounds) {
                    restore(decor, saved)
                    appliedTransform = null
                }
            } else {
                cancelPositionAnimation(decor)
                restore(decor, saved)
                appliedTransform = null
            }
        } else appliedTransform = null
        assistantRoots.toMap().forEach { (root, savedRoot) ->
            val current = assistantTransforms[root]
            if (animate && current != null && canAnimate(root)) {
                animateTransform(root, current, nativeTransform(savedRoot, true), true, savedRoot.clipBounds) {
                    restore(root, savedRoot)
                    root.removeOnLayoutChangeListener(layoutListener)
                    assistantRoots.remove(root)
                    assistantTransforms.remove(root)
                }
            } else {
                val ownedTransform = current != null || positionAnimators.containsKey(root)
                cancelPositionAnimation(root)
                if (ownedTransform) restore(root, savedRoot)
                root.removeOnLayoutChangeListener(layoutListener)
                assistantRoots.remove(root)
                assistantTransforms.remove(root)
            }
        }
    }

    private fun captureOriginal(view: View): OriginalTransform {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return OriginalTransform(
            pivotX = view.pivotX,
            pivotY = view.pivotY,
            scaleX = view.scaleX,
            scaleY = view.scaleY,
            translationX = view.translationX,
            translationY = view.translationY,
            animationMatrix = view.animationMatrix?.let(::Matrix),
            clipBounds = view.clipBounds?.let(::Rect),
            screenX = location[0],
            screenY = location[1],
        )
    }

    private fun restore(view: View, saved: OriginalTransform) {
        view.pivotX = saved.pivotX
        view.pivotY = saved.pivotY
        view.scaleX = saved.scaleX
        view.scaleY = saved.scaleY
        view.translationX = saved.translationX
        view.translationY = saved.translationY
        view.animationMatrix = saved.animationMatrix
        view.clipBounds = saved.clipBounds
    }

    private fun ensureMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Launcher transforms must run on the main thread"
        }
    }

    private fun isAssistantPanel(view: View): Boolean {
        if (view.display?.displayId != Display.DEFAULT_DISPLAY) return false
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return false
        // The ColorOS 17 panel is a TYPE_APPLICATION window attached to Launcher's
        // HOME token, with the package itself as its title. HOME's decor transform
        // does not affect that separate window. Profile/settings BASE_APPLICATION
        // windows have their own tasks and are fitted by SystemUI instead.
        return params.type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW ||
            (params.type == WindowManager.LayoutParams.TYPE_APPLICATION &&
                params.title?.toString() == view.context.packageName)
    }

    private val REAPPLY_DELAYS_MS = longArrayOf(0L, 120L, 300L, 600L, 1_200L, 2_000L)
}
