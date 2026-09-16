package io.github.lujinxin.nextep.launcher

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.animation.DecelerateInterpolator
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.graphics.Matrix
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
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
    // ColorOS also reads the workspace gate from its launcher animation thread.
    @Volatile
    private var active = false
    private var applyGeneration = 0
    private val handler = Handler(Looper.getMainLooper())

    fun attach(view: View) {
        ensureMainThread()
        if (!LauncherPackageResolver.isWorkspaceSurface(view.context)) return

        val decor = view.rootView ?: view
        if (decor.width <= 0 || decor.height <= 0) {
            decor.post { attach(decor) }
            return
        }

        if (LauncherPackageResolver.isAssistantScreen(decor.context)) {
            assistantRoots.getOrPut(decor) { captureOriginal(decor) }
        } else if (decorReference.get() !== decor) {
            restoreCurrentDecor()
            decorReference = WeakReference(decor)
            original = captureOriginal(decor)
        }

        if (active) scheduleApply()
    }

    fun attach(activity: android.app.Activity) {
        activity.window?.decorView?.let(::attach)
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
        active = requestedActive
        applyGeneration += 1
        val decor = decorReference.get() ?: assistantRoots.keys.firstOrNull()
        if (decor == null) {
            NeXtepLog.info(
                "launcher_transform",
                "active=$active pending=true; waiting for workspace surface DecorView attach",
            )
            return true
        }
        if (active) scheduleApply() else restoreCurrentDecor()
        NeXtepLog.info("launcher_transform", "active=$active decor=${decor.javaClass.name}")
        return true
    }

    fun isActive(): Boolean = active

    fun mapToContent(event: MotionEvent?): TouchCoordinateMapper.Transform? {
        val transform = if (active) appliedTransform else null
        return transform?.takeIf { TouchCoordinateMapper.toContent(event, it) }
    }

    fun mapToContent(view: View, event: MotionEvent?): TouchCoordinateMapper.Transform? {
        val transform = if (active) assistantTransforms[view.rootView ?: view] else null
        return transform?.takeIf { TouchCoordinateMapper.toContent(event, it) }
    }

    fun mapToScreen(event: MotionEvent?, transform: TouchCoordinateMapper.Transform?) {
        TouchCoordinateMapper.toScreen(event, transform)
    }

    private fun applyTo(decor: View) {
        val displayMetrics = decor.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val geometry = WorkspaceGeometry.forDisplay(
            screenWidth,
            screenHeight,
            SystemServerWorkspaceBridge.sidebarSide(decor.context),
        )
        val isAssistant = LauncherPackageResolver.isAssistantScreen(decor.context)
        val saved = assistantRoots[decor]
        val transform = if (isAssistant && saved != null) {
            TouchCoordinateMapper.Transform(
                scaleX = (geometry.contentWidth.toFloat() / decor.width).coerceIn(0.5f, 1.25f),
                scaleY = (geometry.contentHeight.toFloat() / decor.height).coerceIn(0.5f, 1.25f),
                // The assistant window itself is horizontally translated by Launcher while
                // swiping between pages. Its animation matrix must stay window-local; folding
                // the transient screen location (often -screenWidth while off-screen) into this
                // translation pushes the scaled page outside the workspace.
                translationX = geometry.contentLeft.toFloat(),
                translationY = geometry.contentTop.toFloat(),
            )
        } else {
            TouchCoordinateMapper.Transform(
                scaleX = geometry.contentWidth.toFloat() / screenWidth,
                scaleY = geometry.contentHeight.toFloat() / screenHeight,
                translationX = geometry.contentLeft.toFloat(),
                translationY = geometry.contentTop.toFloat(),
            )
        }

        val current = if (isAssistant) assistantTransforms[decor] else appliedTransform
        // Delayed reapplication must not snap or restart an in-flight side switch.
        if (positionAnimators[decor]?.isRunning == true && positionTargets[decor] == transform) return
        positionAnimators.remove(decor)?.cancel()
        positionTargets[decor] = transform
        if (current != null && current.translationX != transform.translationX &&
            current.scaleX == transform.scaleX && current.scaleY == transform.scaleY &&
            current.translationY == transform.translationY
        ) {
            val animator = ValueAnimator.ofFloat(current.translationX, transform.translationX)
            animator.duration = 260L
            animator.interpolator = DecelerateInterpolator(1.6f)
            animator.addUpdateListener {
                writeTransform(decor, transform.copy(translationX = it.animatedValue as Float), isAssistant)
            }
            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (positionAnimators[decor] === animation) {
                        positionAnimators.remove(decor)
                        positionTargets.remove(decor)
                    }
                }
            })
            positionAnimators[decor] = animator
            animator.start()
        } else {
            writeTransform(decor, transform, isAssistant)
        }
    }

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

    private fun restoreCurrentDecor() {
        positionAnimators.values.toList().forEach { it.cancel() }
        positionAnimators.clear()
        positionTargets.clear()
        val decor = decorReference.get()
        val saved = original
        if (decor != null && saved != null) {
            decor.pivotX = saved.pivotX
            decor.pivotY = saved.pivotY
            decor.scaleX = saved.scaleX
            decor.scaleY = saved.scaleY
            decor.translationX = saved.translationX
            decor.translationY = saved.translationY
            decor.animationMatrix = saved.animationMatrix
        }
        appliedTransform = null
        assistantRoots.toMap().forEach { (root, savedRoot) -> restore(root, savedRoot) }
        assistantRoots.clear()
        assistantTransforms.clear()
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
    }

    private fun ensureMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Launcher transforms must run on the main thread"
        }
    }

    private val REAPPLY_DELAYS_MS = longArrayOf(0L, 120L, 300L, 600L, 1_200L, 2_000L)
}
