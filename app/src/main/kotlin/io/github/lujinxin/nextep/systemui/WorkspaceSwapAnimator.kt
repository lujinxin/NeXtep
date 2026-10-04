package io.github.lujinxin.nextep.systemui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import io.github.lujinxin.nextep.logging.NeXtepLog

/** Animate disposable previews first; perform the display move while the final
 * preview covers relayout, then fade to the live buffers. Nothing retains an app
 * frame after the transition ends or the workspace is hidden/locked. */
internal class WorkspaceSwapAnimator(private val context: Context) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var overlay: SwapPreviewView? = null
    private var animator: ValueAnimator? = null
    private var generation = 0

    fun animate(
        source: Bitmap?, target: Bitmap?,
        start: RectF, sourceBounds: RectF, targetBounds: RectF,
        screenWidth: Int, screenHeight: Int,
        move: () -> Unit,
    ) {
        cancel()
        val windowType = SystemUiWindowTypeResolver.resolve()
        if (windowType == null || source == null) {
            source?.recycle()
            target?.recycle()
            move()
            return
        }
        val token = ++generation
        val preview = SwapPreviewView(context, source, target, start, sourceBounds, targetBounds)
        val params = WindowManager.LayoutParams(
            screenWidth, screenHeight, windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            title = "NeXtepSwapPreview"
            gravity = Gravity.TOP or Gravity.LEFT
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val added = runCatching { manager.addView(preview, params) }.isSuccess
        if (!added) {
            preview.release()
            move()
            return
        }
        overlay = preview
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                preview.progress = it.animatedValue as Float
                preview.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (generation != token) return
                    animator = null
                    val moved = runCatching(move).onFailure {
                        NeXtepLog.warn("slot_drag", "Exchange callback failed", it)
                        cancel()
                    }.isSuccess
                    if (!moved || generation != token) return
                    preview.postDelayed({
                        if (generation != token) return@postDelayed
                        preview.animate().alpha(0f).setDuration(160L).withEndAction {
                            if (generation == token) cancel()
                        }.start()
                    }, 300L)
                }
            })
            start()
        }
    }

    fun cancel() {
        generation += 1
        animator?.cancel()
        animator = null
        val previous = overlay
        overlay = null
        previous?.animate()?.cancel()
        if (previous != null) {
            runCatching { manager.removeViewImmediate(previous) }
            previous.release()
        }
    }
}

private class SwapPreviewView(
    context: Context,
    private val source: Bitmap,
    private val target: Bitmap?,
    private val start: RectF,
    private val sourceBounds: RectF,
    private val targetBounds: RectF,
) : View(context) {
    var progress = 0f
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(165, 210, 242, 245)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val destination = RectF()
    private val sourceRect = Rect()
    private val clipPath = android.graphics.Path()
    private val cornerRadius = resources.displayMetrics.density * 8f
    private var released = false

    override fun onDraw(canvas: Canvas) {
        if (released) return
        if (target != null) drawFrame(canvas, target, targetBounds, sourceBounds)
        drawFrame(canvas, source, start, targetBounds)
    }

    private fun drawFrame(canvas: Canvas, bitmap: Bitmap, from: RectF, to: RectF) {
        fun interpolate(a: Float, b: Float) = a + (b - a) * progress
        destination.set(interpolate(from.left, to.left), interpolate(from.top, to.top),
            interpolate(from.right, to.right), interpolate(from.bottom, to.bottom))
        val save = canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(destination, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
        canvas.clipPath(clipPath)
        sourceRect.set(0, 0, bitmap.width, bitmap.height)
        canvas.drawBitmap(bitmap, sourceRect, destination, bitmapPaint)
        canvas.restoreToCount(save)
        canvas.drawRoundRect(destination, cornerRadius, cornerRadius, borderPaint)
    }

    fun release() {
        if (released) return
        released = true
        source.recycle()
        target?.recycle()
    }
}
