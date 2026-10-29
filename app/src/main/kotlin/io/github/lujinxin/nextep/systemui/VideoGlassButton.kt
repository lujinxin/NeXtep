package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.WindowManager
import android.widget.Button
import io.github.lujinxin.nextep.logging.NeXtepLog
import java.util.function.Consumer
import kotlin.math.roundToInt

/** Compositor-backed glass over live video, with no frame copies or video texture sampling. */
internal class VideoGlassButton(context: Context, initialIcon: Icon) : View(context) {
    enum class Icon { PLAY, PAUSE, PLAY_PAUSE, FIT, FILL, RETURN, CLOSE }

    private val density = resources.displayMetrics.density
    private val glyph = Path()
    private val outline = Path()
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var icon = initialIcon
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val material = VideoGlassMaterial(density)
    private var nativeBlur: Drawable? = null
    private var blurEnabled = false
    private var observingBlur = false
    private var blurFailureReported = false
    private val blurChanged = Consumer<Boolean> { enabled ->
        if (blurEnabled != enabled) {
            blurEnabled = enabled
            rebuildBackground()
        }
    }

    init {
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        isClickable = true
        isFocusable = true
        updateGlyph()
        rebuildBackground()
    }

    fun setIcon(value: Icon) {
        if (icon == value) return
        icon = value
        updateGlyph()
        invalidate()
    }

    private fun updateGlyph() {
        glyph.reset()
        outline.reset()
        when (icon) {
            Icon.PLAY -> {
                glyph.moveTo(8f, 4f)
                glyph.lineTo(20f, 12f)
                glyph.lineTo(8f, 20f)
                glyph.close()
            }
            Icon.PAUSE -> {
                glyph.addRect(6.5f, 4f, 10f, 20f, Path.Direction.CW)
                glyph.addRect(14f, 4f, 17.5f, 20f, Path.Direction.CW)
            }
            Icon.PLAY_PAUSE -> {
                glyph.moveTo(3f, 5f)
                glyph.lineTo(12f, 12f)
                glyph.lineTo(3f, 19f)
                glyph.close()
                glyph.addRect(15f, 5f, 17.5f, 19f, Path.Direction.CW)
                glyph.addRect(20f, 5f, 22.5f, 19f, Path.Direction.CW)
            }
            Icon.FILL -> {
                outline.moveTo(9f, 4f)
                outline.lineTo(4f, 4f)
                outline.lineTo(4f, 9f)
                outline.moveTo(15f, 4f)
                outline.lineTo(20f, 4f)
                outline.lineTo(20f, 9f)
                outline.moveTo(4f, 15f)
                outline.lineTo(4f, 20f)
                outline.lineTo(9f, 20f)
                outline.moveTo(20f, 15f)
                outline.lineTo(20f, 20f)
                outline.lineTo(15f, 20f)
            }
            Icon.FIT -> {
                outline.addRoundRect(2f, 4f, 22f, 20f, 1f, 1f, Path.Direction.CW)
                outline.addRect(7f, 5f, 17f, 19f, Path.Direction.CW)
            }
            Icon.RETURN -> {
                outline.addRoundRect(2f, 4f, 22f, 20f, 2f, 2f, Path.Direction.CW)
                glyph.addRoundRect(12f, 11f, 19f, 17f, 0.8f, 0.8f, Path.Direction.CW)
            }
            Icon.CLOSE -> {
                outline.moveTo(6f, 6f)
                outline.lineTo(18f, 18f)
                outline.moveTo(18f, 6f)
                outline.lineTo(6f, 18f)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(dp(20).toFloat(), width.toFloat(), height.toFloat())
        if (size <= 0f) return
        val save = canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        canvas.scale(size / 24f, size / 24f)
        glyphPaint.style = Paint.Style.FILL
        canvas.drawPath(glyph, glyphPaint)
        glyphPaint.style = Paint.Style.STROKE
        canvas.drawPath(outline, glyphPaint)
        canvas.restoreToCount(save)
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        runCatching {
            blurEnabled = windowManager?.isCrossWindowBlurEnabled == true
            windowManager?.addCrossWindowBlurEnabledListener(context.mainExecutor, blurChanged)
            observingBlur = windowManager != null
        }.onFailure { reportBlurFailure(it) }
        // ViewRoot and its hardware renderer are available after attachment.
        post { if (isAttachedToWindow) rebuildBackground() }
    }

    override fun onDetachedFromWindow() {
        if (observingBlur) {
            runCatching { windowManager?.removeCrossWindowBlurEnabledListener(blurChanged) }
            observingBlur = false
        }
        clearNativeBlur()
        super.onDetachedFromWindow()
    }

    private fun rebuildBackground() {
        clearNativeBlur()
        val backdrop = if (blurEnabled && isAttachedToWindow && isHardwareAccelerated) {
            runCatching {
                val root = View::class.java.getMethod("getViewRootImpl").invoke(this)
                    ?: return@runCatching null
                val drawable = root.javaClass.getMethod("createBackgroundBlurDrawable")
                    .invoke(root) as? Drawable ?: return@runCatching null
                // Keep a reference before configuration so a partial failure also removes its region.
                nativeBlur = drawable
                drawable.javaClass.getMethod("setBlurRadius", Int::class.javaPrimitiveType)
                    .invoke(drawable, dp(12))
                drawable.javaClass.getMethod("setCornerRadius", Float::class.javaPrimitiveType)
                    .invoke(drawable, 18f * density)
                drawable
            }.onFailure {
                clearNativeBlur()
                reportBlurFailure(it)
            }.getOrNull()
        } else null
        val layers = if (backdrop == null) material else LayerDrawable(arrayOf(backdrop, material))
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f * density
            setColor(Color.WHITE)
        }
        val ripple = RippleDrawable(ColorStateList.valueOf(Color.argb(48, 255, 255, 255)), layers, mask)
        // A compact 36dp glass circle keeps a full 48dp touch target.
        background = InsetDrawable(ripple, dp(6), dp(6), dp(6), dp(6))
    }

    private fun clearNativeBlur() {
        nativeBlur?.let { drawable ->
            drawable.setVisible(false, false)
            runCatching {
                drawable.javaClass.getMethod("setBlurRadius", Int::class.javaPrimitiveType)
                    .invoke(drawable, 0)
            }
        }
        nativeBlur = null
    }

    private fun reportBlurFailure(error: Throwable) {
        if (blurFailureReported) return
        blurFailureReported = true
        NeXtepLog.warn("control_video_glass", "Native background blur unavailable; keeping translucent glass", error)
    }

    private fun dp(value: Int) = (value * density).roundToInt()
}

/** Neutral tint, curved highlights and a fine refractive rim also work when blur is disabled. */
private class VideoGlassMaterial(private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = RectF()
    private val rimFrame = RectF()
    private var sheen: Shader? = null
    private var rim: Shader? = null
    private var opacity = 255

    override fun onBoundsChange(bounds: Rect) {
        frame.set(bounds)
        rimFrame.set(frame)
        rimFrame.inset(density * 0.4f, density * 0.4f)
        if (bounds.isEmpty) return
        sheen = LinearGradient(frame.left, frame.top, frame.left, frame.bottom,
            intArrayOf(Color.argb(72, 255, 255, 255), Color.argb(10, 255, 255, 255), Color.argb(22, 0, 0, 0)),
            floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP)
        rim = SweepGradient(frame.centerX(), frame.centerY(),
            intArrayOf(Color.argb(45, 255, 255, 255), Color.argb(185, 255, 255, 255),
                Color.argb(38, 255, 255, 255), Color.argb(150, 255, 255, 255), Color.argb(45, 255, 255, 255)),
            floatArrayOf(0f, 0.2f, 0.47f, 0.72f, 1f))
    }

    override fun draw(canvas: Canvas) {
        if (frame.isEmpty) return
        val radius = frame.height() / 2f
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = Color.argb(68 * opacity / 255, 20, 24, 31)
        canvas.drawRoundRect(frame, radius, radius, paint)
        paint.color = Color.WHITE
        paint.alpha = opacity
        paint.shader = sheen
        canvas.drawRoundRect(frame, radius, radius, paint)
        paint.shader = rim
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density * 0.8f
        canvas.drawRoundRect(rimFrame, radius, radius, paint)
        paint.shader = null
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, bounds.height() / 2f)
    }

    override fun setAlpha(alpha: Int) {
        opacity = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
