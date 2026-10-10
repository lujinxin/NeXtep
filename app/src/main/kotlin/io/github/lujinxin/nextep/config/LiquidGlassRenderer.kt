package io.github.lujinxin.nextep.config

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.SweepGradient
import io.github.lujinxin.nextep.logging.NeXtepLog
import kotlin.math.roundToInt

/** Records only the page, then composites glass and an independent moving lens. */
internal class LiquidGlassRenderer(private val context: Context) {
    private val density = context.resources.displayMetrics.density
    private val margin = (40f * density).roundToInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val inset = RectF()
    private val pageNode = RenderNode("NeXtep-PageBackdrop")
    private val glassNode = RenderNode("NeXtep-GlassBackdrop")
    private val selectionNode = RenderNode("NeXtep-SelectionLens")
    private var glassShader: RuntimeShader? = null
    private var selectionShader: RuntimeShader? = null
    private var opticsAvailable = true
    private var captured = false
    private var captureWidth = 0
    private var captureHeight = 0
    private val previousBounds = RectF()
    private val vibrancy = RenderEffect.createColorFilterEffect(
        ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) }),
    )
    private val blur = RenderEffect.createBlurEffect(4f * density, 4f * density, vibrancy, Shader.TileMode.CLAMP)

    fun capture(source: RenderNode, width: Int, height: Int, sourceX: Int, sourceY: Int) {
        captureWidth = width + 2 * margin
        captureHeight = height + 2 * margin
        pageNode.setPosition(0, 0, captureWidth, captureHeight)
        val page = pageNode.beginRecording(captureWidth, captureHeight)
        try {
            page.drawColor(SettingsPalette.page(context))
            page.translate((sourceX + margin).toFloat(), (sourceY + margin).toFloat())
            page.drawRenderNode(source)
        } finally { pageNode.endRecording() }
        glassNode.setPosition(-margin, -margin, width + margin, height + margin)
        val glass = glassNode.beginRecording(captureWidth, captureHeight)
        try { glass.drawRenderNode(pageNode) }
        finally { glassNode.endRecording() }
        captured = true
        glassNode.setRenderEffect(if (previousBounds.isEmpty) blur else baseEffect(previousBounds))
    }

    private fun baseEffect(bounds: RectF): RenderEffect {
        return opticalEffect(bounds, 24f * density, 24f * density, false)?.let {
            RenderEffect.createChainEffect(it, blur)
        } ?: blur
    }

    private fun opticalEffect(bounds: RectF, height: Float, amount: Float, selection: Boolean): RenderEffect? {
        if (!opticsAvailable) return null
        return runCatching {
            val shader = if (selection) selectionShader ?: LiquidGlassLens.newShader(true).also { selectionShader = it }
                else glassShader ?: LiquidGlassLens.newShader(false).also { glassShader = it }
            LiquidGlassLens.effect(shader, bounds, margin.toFloat(), height, amount, selection)
        }.onFailure {
            opticsAvailable = false
            NeXtepLog.warn("settings_glass", "Lens shader unavailable; keeping the GPU blur", it)
        }.getOrNull()
    }

    fun drawBase(canvas: Canvas, bounds: RectF) {
        if (bounds.isEmpty) return
        if (previousBounds != bounds) {
            previousBounds.set(bounds)
            glassNode.setRenderEffect(baseEffect(bounds))
        }
        val save = canvas.save()
        roundedClip(canvas, bounds)
        if (captured && canvas.isHardwareAccelerated) canvas.drawRenderNode(glassNode)
        else {
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = SettingsPalette.page(context)
            canvas.drawRect(bounds, paint)
        }
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = SettingsPalette.glassTint(context)
        canvas.drawRect(bounds, paint)
        canvas.restoreToCount(save)
        drawRim(canvas, bounds, 0.75f)
    }

    fun drawSelection(
        canvas: Canvas, barBounds: RectF, lensBounds: RectF, press: Float,
        scaleX: Float, scaleY: Float, panelOffset: Float, panelScale: Float,
        drawAccentItems: (Canvas) -> Unit,
    ) {
        if (captureWidth == 0 || captureHeight == 0 || !canvas.isHardwareAccelerated) {
            val save = canvas.save()
            canvas.translate(panelOffset, 0f)
            roundedClip(canvas, lensBounds)
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.color = SettingsPalette.container(context)
            canvas.drawRect(lensBounds, paint)
            drawAccentItems(canvas)
            canvas.restoreToCount(save)
            return
        }
        // Like Miuix's CombinedBackdrop, the lens sees the page and accent-colored tabs.
        selectionNode.setPosition(-margin, -margin, captureWidth - margin, captureHeight - margin)
        val recording = selectionNode.beginRecording(captureWidth, captureHeight)
        try {
            recording.translate(margin.toFloat(), margin.toFloat())
            recording.scale(panelScale, panelScale, barBounds.centerX(), barBounds.centerY())
            drawBase(recording, barBounds)
            drawAccentItems(recording)
        } finally { selectionNode.endRecording() }
        selectionNode.setRenderEffect(opticalEffect(lensBounds,
            10f * density * press, 14f * density * press, true))
        val save = canvas.save()
        canvas.translate(panelOffset, 0f)
        canvas.scale(scaleX, scaleY, lensBounds.centerX(), lensBounds.centerY())
        roundedClip(canvas, lensBounds)
        canvas.drawRenderNode(selectionNode)
        paint.style = Paint.Style.FILL
        paint.shader = null
        val neutral = if (SettingsPalette.isDark(context)) Color.WHITE else Color.BLACK
        paint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(neutral, (17.85f * (1f - press)).roundToInt())
        canvas.drawRect(lensBounds, paint)
        paint.color = Color.argb((7.65f * press).roundToInt(), 0, 0, 0)
        canvas.drawRect(lensBounds, paint)
        drawRim(canvas, lensBounds, press)
        canvas.restoreToCount(save)
    }

    private fun roundedClip(canvas: Canvas, bounds: RectF) {
        clip.reset()
        clip.addRoundRect(bounds, bounds.height() / 2f, bounds.height() / 2f, Path.Direction.CW)
        canvas.clipPath(clip)
    }

    private fun drawRim(canvas: Canvas, bounds: RectF, strength: Float) {
        if (strength <= 0f) return
        val high = if (SettingsPalette.isDark(context)) 125 else 235
        val low = if (SettingsPalette.isDark(context)) 16 else 35
        fun alpha(value: Int) = Color.argb((value * strength).roundToInt(), 255, 255, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = Color.WHITE
        paint.shader = SweepGradient(bounds.centerX(), bounds.centerY(),
            intArrayOf(alpha(low), alpha(high), alpha(low), alpha(high), alpha(low)),
            floatArrayOf(0f, 0.2f, 0.45f, 0.7f, 1f))
        inset.set(bounds)
        inset.inset(density / 2f, density / 2f)
        canvas.drawRoundRect(inset, inset.height() / 2f, inset.height() / 2f, paint)
        paint.shader = null
        paint.style = Paint.Style.FILL
    }

    fun clear() {
        pageNode.discardDisplayList()
        glassNode.discardDisplayList()
        selectionNode.discardDisplayList()
        captured = false
        captureWidth = 0
        captureHeight = 0
        previousBounds.setEmpty()
    }
}
