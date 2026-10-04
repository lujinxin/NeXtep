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
    fun identity(context: Context): String? = runCatching {
        val manager = WallpaperManager.getInstance(context)
        "${manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)}:${manager.wallpaperInfo?.component}"
    }.getOrNull()

    @SuppressLint("MissingPermission")
    fun capture(context: Context, width: Int, height: Int): Bitmap? = runCatching {
        // ColorOS 17 uses a live service even for some desktop wallpaper presets.
        // WallpaperManager.drawable can then contain the previous static wallpaper.
        // Capture only the wallpaper surface, never the screen's apps or overlays.
        captureRenderedWallpaper()?.let { snapshot ->
            try {
                val software = if (snapshot.config == Bitmap.Config.HARDWARE) {
                    snapshot.copy(Bitmap.Config.ARGB_8888, false) ?: return@runCatching null
                } else snapshot
                try {
                    return@runCatching fit(software, width, height)
                } finally {
                    if (software !== snapshot) software.recycle()
                }
            } finally {
                snapshot.recycle()
            }
        }
        val manager = WallpaperManager.getInstance(context)
        // A hidden live surface has no frame to capture. Keep a matching cached frame
        // in the window controller rather than displaying an unrelated static image.
        if (manager.wallpaperInfo != null) return@runCatching null
        manager.forgetLoadedWallpaper()
        val wallpaper = manager.drawable?.constantState?.newDrawable(context.resources)?.mutate()
            ?: return@runCatching null
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

    fun fit(source: Bitmap, width: Int, height: Int): Bitmap? = runCatching {
        val fitted = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            val scale = maxOf(fitted.width.toFloat() / source.width, fitted.height.toFloat() / source.height)
            val canvas = Canvas(fitted)
            canvas.translate((fitted.width - source.width * scale) / 2f, (fitted.height - source.height * scale) / 2f)
            canvas.scale(scale, scale)
            canvas.drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            fitted
        } catch (error: Throwable) {
            fitted.recycle()
            throw error
        }
    }.onFailure { error ->
        NeXtepLog.warn("wallpaper_backdrop", "Unable to fit wallpaper snapshot", error)
    }.getOrNull()

    private fun captureRenderedWallpaper(): Bitmap? = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val service = global.getDeclaredMethod("getWindowManagerService").apply {
            isAccessible = true
        }.invoke(null) ?: return@runCatching null
        Class.forName("android.view.IWindowManager")
            .getDeclaredMethod("screenshotWallpaper")
            .apply { isAccessible = true }
            .invoke(service) as? Bitmap
    }.onFailure { error ->
        NeXtepLog.warn("wallpaper_backdrop", "Wallpaper surface capture unavailable", error)
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
