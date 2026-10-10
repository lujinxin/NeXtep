package io.github.lujinxin.nextep.systemui

import android.graphics.Bitmap
import android.graphics.Color

/** Bounded artwork sampling. Call off the UI thread, especially for hardware bitmaps. */
internal object MediaArtworkPalette {
    data class Colors(val start: Int, val end: Int)

    fun extract(artwork: Bitmap): Colors? {
        if (artwork.isRecycled) return null
        var scaled: Bitmap? = null
        var software: Bitmap? = null
        try {
            val source = if (artwork.config == Bitmap.Config.HARDWARE) {
                val sample = Bitmap.createScaledBitmap(
                    artwork, minOf(SAMPLE_SIZE, artwork.width), minOf(SAMPLE_SIZE, artwork.height), true,
                ).also { scaled = it }
                sample.copy(Bitmap.Config.ARGB_8888, false)?.also { software = it } ?: return null
            } else artwork
            val weights = FloatArray(512)
            val reds = FloatArray(512)
            val greens = FloatArray(512)
            val blues = FloatArray(512)
            val hsv = FloatArray(3)
            for (y in 0 until SAMPLE_SIZE) {
                for (x in 0 until SAMPLE_SIZE) {
                    val color = source.getPixel(
                        ((x + 0.5f) * source.width / SAMPLE_SIZE).toInt().coerceAtMost(source.width - 1),
                        ((y + 0.5f) * source.height / SAMPLE_SIZE).toInt().coerceAtMost(source.height - 1),
                    )
                    val alpha = Color.alpha(color)
                    if (alpha < 32) continue
                    Color.colorToHSV(color, hsv)
                    // Keep white margins and black borders from overwhelming the cover's color.
                    val exposureWeight = if (hsv[2] in 0.08f..0.94f) 1f else 0.25f
                    val weight = alpha / 255f * (0.35f + 0.65f * hsv[1]) * exposureWeight
                    val red = Color.red(color)
                    val green = Color.green(color)
                    val blue = Color.blue(color)
                    val bin = ((red shr 5) shl 6) or ((green shr 5) shl 3) or (blue shr 5)
                    weights[bin] += weight
                    reds[bin] += red * weight
                    greens[bin] += green * weight
                    blues[bin] += blue * weight
                }
            }
            val dominant = weights.indices.maxByOrNull { weights[it] } ?: return null
            val weight = weights[dominant].takeIf { it > 0f } ?: return null
            Color.colorToHSV(Color.rgb(
                (reds[dominant] / weight).toInt().coerceIn(0, 255),
                (greens[dominant] / weight).toInt().coerceIn(0, 255),
                (blues[dominant] / weight).toInt().coerceIn(0, 255),
            ), hsv)
            // A restrained saturation and bounded brightness keep white controls legible.
            hsv[1] = (hsv[1] * 0.5f).coerceAtMost(0.32f)
            hsv[2] = 0.30f + 0.10f * hsv[2]
            val start = Color.HSVToColor(hsv)
            hsv[1] *= 0.9f
            hsv[2] *= 0.74f
            return Colors(start, Color.HSVToColor(hsv))
        } finally {
            software?.takeUnless { it === artwork || it === scaled }?.recycle()
            scaled?.takeUnless { it === artwork }?.recycle()
        }
    }

    private const val SAMPLE_SIZE = 32
}
