package io.github.lujinxin.nextep.systemui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import io.github.lujinxin.nextep.config.WorkspaceConfigContract
import io.github.lujinxin.nextep.logging.NeXtepLog

/** Own previews only for the exchange: animate both frames, cover the display
 * move, then hand off to the live buffers. Cancellation releases every frame. */
internal class WorkspaceSwapAnimator(private val context: Context) {
    private var overlay: SwapPreviewView? = null
    private var animator: ValueAnimator? = null
    private var completion: (() -> Unit)? = null
    private var generation = 0

    fun animate(
        source: Bitmap?, target: Bitmap?,
        start: RectF, sourceBounds: RectF, targetBounds: RectF,
        host: FrameLayout,
        backdrop: Drawable?,
        durationMs: Int,
        move: () -> Boolean,
        isLiveReady: () -> Boolean,
        onFinished: () -> Unit,
    ) {
        cancel()
        if (!host.isAttachedToWindow || !host.isHardwareAccelerated || source == null || start.isEmpty ||
            sourceBounds.isEmpty || targetBounds.isEmpty
        ) {
            source?.recycle()
            if (target !== source) target?.recycle()
            try { move() } finally { onFinished() }
            return
        }
        val token = ++generation
        val preview = SwapPreviewView(context, source, target, start, sourceBounds, targetBounds, backdrop)
        // Reuse the non-touchable full-display content panel. Creating and
        // removing another system window can disturb HOME visibility on exchange.
        val added = runCatching {
            host.addView(preview, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }.onFailure {
            NeXtepLog.warn("workspace_swap", "Preview host unavailable", it)
        }.isSuccess
        if (!added) {
            preview.release()
            try { move() } finally { onFinished() }
            return
        }
        overlay = preview
        completion = onFinished
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = WorkspaceConfigContract.normalizeSwapAnimationDurationMs(durationMs).toLong()
            interpolator = PathInterpolator(0.22f, 0f, 0.12f, 1f)
            addUpdateListener {
                preview.progress = it.animatedValue as Float
                preview.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (generation != token) return
                    animator = null
                    preview.progress = 1f
                    // Wait for the final preview to reach the render swap chain
                    // before a synchronous Binder move can block task relayout.
                    preview.viewTreeObserver.registerFrameCommitCallback {
                        preview.post {
                            if (generation != token) return@post
                            val moved = runCatching(move).onFailure {
                                NeXtepLog.warn("workspace_swap", "Exchange callback failed", it)
                            }.getOrDefault(false)
                            if (generation != token) return@post
                            if (!moved) {
                                cancel()
                                return@post
                            }
                            awaitLiveFrame(preview, token, SystemClock.uptimeMillis(), isLiveReady)
                        }
                    }
                    preview.invalidate()
                }
            })
            start()
        }
    }

    private fun awaitLiveFrame(
        preview: SwapPreviewView,
        token: Int,
        movedAt: Long,
        isLiveReady: () -> Boolean,
    ) {
        if (generation != token) return
        val elapsed = SystemClock.uptimeMillis() - movedAt
        val ready = elapsed >= MIN_HANDOFF_DELAY_MS && runCatching(isLiveReady).getOrDefault(false)
        if (ready || elapsed >= MAX_HANDOFF_DELAY_MS) {
            if (!ready) NeXtepLog.warn("workspace_swap", "Live frame handoff timed out")
            // Reveal the live buffers together on the next frame. Crossfading
            // different app frames creates double edges and makes the tail blur.
            preview.postOnAnimation {
                if (generation == token) cancel()
            }
        } else {
            preview.postOnAnimation { awaitLiveFrame(preview, token, movedAt, isLiveReady) }
        }
    }

    fun cancel() {
        generation += 1
        animator?.cancel()
        animator = null
        val previous = overlay
        val finished = completion
        overlay = null
        completion = null
        if (previous != null) {
            runCatching { (previous.parent as? FrameLayout)?.removeView(previous) }
            previous.release()
        }
        finished?.invoke()
    }

    private companion object {
        const val MIN_HANDOFF_DELAY_MS = 80L
        const val MAX_HANDOFF_DELAY_MS = 600L
    }
}

private class SwapPreviewView(
    context: Context,
    private val source: Bitmap,
    private val target: Bitmap?,
    private val start: RectF,
    private val sourceBounds: RectF,
    private val targetBounds: RectF,
    private val backdrop: Drawable?,
) : View(context) {
    var progress = 0f
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val destination = RectF()
    private val fitted = RectF()
    private val sourceRect = Rect()
    private val clipPath = Path()
    private val backdropClip = Path().apply {
        addRect(sourceBounds, Path.Direction.CW)
        if (target != null) addRect(targetBounds, Path.Direction.CW)
    }
    private val maximumCornerRadius = resources.displayMetrics.density * 10f
    private var released = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    override fun onDraw(canvas: Canvas) {
        if (released) return
        // Erase the stationary originals only inside the participating windows.
        // Other slots and the control area remain visible throughout the exchange.
        val save = canvas.save()
        canvas.clipPath(backdropClip)
        canvas.drawColor(Color.BLACK)
        backdrop?.setBounds(0, 0, width, height)
        backdrop?.draw(canvas)
        canvas.restoreToCount(save)

        if (target != null) drawFrame(canvas, target, targetBounds, sourceBounds)
        drawFrame(canvas, source, start, targetBounds)
    }

    private fun drawFrame(canvas: Canvas, bitmap: Bitmap, from: RectF, to: RectF) {
        fun interpolate(a: Float, b: Float) = a + (b - a) * progress
        destination.set(interpolate(from.left, to.left), interpolate(from.top, to.top),
            interpolate(from.right, to.right), interpolate(from.bottom, to.bottom))
        val radius = minOf(destination.width(), destination.height()).times(0.04f)
            .coerceAtMost(maximumCornerRadius)
        val save = canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(destination, radius, radius, Path.Direction.CW)
        canvas.clipPath(clipPath)
        canvas.drawRect(destination, backgroundPaint)
        // Preserve the app's proportions when navigation insets or rotated
        // panel bounds introduce small differences between the two rectangles.
        val scale = minOf(destination.width() / bitmap.width, destination.height() / bitmap.height)
        val halfWidth = bitmap.width * scale / 2f
        val halfHeight = bitmap.height * scale / 2f
        fitted.set(destination.centerX() - halfWidth, destination.centerY() - halfHeight,
            destination.centerX() + halfWidth, destination.centerY() + halfHeight)
        sourceRect.set(0, 0, bitmap.width, bitmap.height)
        canvas.drawBitmap(bitmap, sourceRect, fitted, bitmapPaint)
        canvas.restoreToCount(save)
    }

    fun release() {
        if (released) return
        released = true
        source.recycle()
        if (target !== source) target?.recycle()
    }
}
