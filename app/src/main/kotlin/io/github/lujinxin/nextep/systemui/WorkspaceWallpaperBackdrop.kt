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
    fun capture(context: Context, width: Int, height: Int): Bitmap? = runCatching {
        val wallpaper = WallpaperManager.getInstance(context).drawable ?: return@runCatching null
        val sampleWidth = (width / BLUR_DOWNSCALE).coerceAtLeast(1)
        val sampleHeight = (height / BLUR_DOWNSCALE).coerceAtLeast(1)
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
        Bitmap.createScaledBitmap(sampled, width, height, true).also {
            if (it !== sampled) sampled.recycle()
        }
    }.onFailure { error ->
        NeXtepLog.warn("wallpaper_backdrop", "Unable to capture desktop wallpaper", error)
    }.getOrNull()

    fun crop(bitmap: Bitmap?, originX: Int, originY: Int): Drawable =
        WallpaperCropDrawable(bitmap, originX, originY)

    private const val BLUR_DOWNSCALE = 18
}

private class WallpaperCropDrawable(
    private val bitmap: Bitmap?,
    private val originX: Int,
    private val originY: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = null
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
        canvas.drawBitmap(source, Rect(left, top, right, bottom), bounds, paint)
        canvas.drawColor(Color.argb(54, 10, 13, 19))
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
