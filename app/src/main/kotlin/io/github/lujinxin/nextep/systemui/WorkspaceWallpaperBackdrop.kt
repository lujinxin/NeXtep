package io.github.lujinxin.nextep.systemui

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import io.github.lujinxin.nextep.logging.NeXtepLog

object WorkspaceWallpaperBackdrop {
    // This code executes inside the hooked SystemUI process, whose Context owns wallpaper access.
    @SuppressLint("MissingPermission")
    fun capture(context: Context, width: Int, height: Int, strength: Int): Bitmap? = runCatching {
        val wallpaper = WallpaperManager.getInstance(context).drawable ?: return@runCatching null
        val sampleWidth = width.coerceAtLeast(1)
        val sampleHeight = height.coerceAtLeast(1)
        val sampled = Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888)
        val sourceWidth = wallpaper.intrinsicWidth.takeIf { it > 0 } ?: width
        val sourceHeight = wallpaper.intrinsicHeight.takeIf { it > 0 } ?: height
        val scale = maxOf(
            sampleWidth.toFloat() / sourceWidth,
            sampleHeight.toFloat() / sourceHeight,
        )
        val drawWidth = (sourceWidth * scale).toInt()
        val drawHeight = (sourceHeight * scale).toInt()
        val left = (sampleWidth - drawWidth) / 2
        val top = (sampleHeight - drawHeight) / 2
        wallpaper.setBounds(left, top, left + drawWidth, top + drawHeight)
        wallpaper.draw(Canvas(sampled))
        sampled
    }.onFailure { error ->
        NeXtepLog.warn("wallpaper_backdrop", "Unable to capture desktop wallpaper", error)
    }.getOrNull()

    fun crop(bitmap: Bitmap?, originX: Int, originY: Int, strength: Int): Drawable =
        WallpaperCropDrawable(bitmap, originX, originY, strength.coerceIn(0, 100))


}

private class WallpaperCropDrawable(
    private val bitmap: Bitmap?,
    private val originX: Int,
    private val originY: Int,
    private val strength: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = null
    }

    // Blur the original full-resolution wallpaper with Android's GPU blur.
    // A quadratic curve preserves detail in the first half of the slider.
    private val blurredWallpaper = bitmap?.takeIf { strength > 0 }?.let { source ->
        android.graphics.RenderNode("NeXtep-FrostedWallpaper").apply {
            setPosition(0, 0, source.width, source.height)
            val recording = beginRecording(source.width, source.height)
            recording.drawBitmap(source, 0f, 0f, paint)
            endRecording()
            val fraction = strength / 100f
            val radius = 32f * fraction * fraction * android.content.res.Resources.getSystem().displayMetrics.density
            setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                radius.coerceAtLeast(0.01f), radius.coerceAtLeast(0.01f), android.graphics.Shader.TileMode.CLAMP,
            ))
        }
    }

    override fun draw(canvas: Canvas) {
        val source = bitmap ?: run {
            canvas.drawColor(Color.rgb(28, 30, 36))
            return
        }
        val left = originX.coerceIn(0, source.width)
        val top = originY.coerceIn(0, source.height)
        val right = (left + bounds.width()).coerceAtMost(source.width)
        val bottom = (top + bounds.height()).coerceAtMost(source.height)
        if (right <= left || bottom <= top) return
        val node = blurredWallpaper
        if (node != null && canvas.isHardwareAccelerated) {
            val save = canvas.save()
            canvas.clipRect(bounds)
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width().toFloat() / (right - left), bounds.height().toFloat() / (bottom - top))
            canvas.translate(-left.toFloat(), -top.toFloat())
            node.alpha = paint.alpha / 255f
            canvas.drawRenderNode(node)
            canvas.restoreToCount(save)
        } else {
            canvas.drawBitmap(source, Rect(left, top, right, bottom), bounds, paint)
        }
        // Neutral translucent layers soften contrast without blurring foreground content.
        if (strength > 0) {
            canvas.drawColor(Color.argb(strength * 32 / 100, 16, 23, 34))
            canvas.drawColor(Color.argb(strength * 20 / 100, 235, 241, 248))
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
