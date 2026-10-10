package io.github.lujinxin.nextep.display

import android.content.Context
import kotlin.math.roundToInt

/**
 * Full-width portrait canvas with the slot's aspect ratio. Apps lay out for the
 * available height instead of stretching their original full-screen buffers.
 * Moving a landscape task between displays can still cause an Android configuration change.
 */
data class SlotGeometry(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
) {
    init {
        require(width > 0 && height > 0) { "Slot dimensions must be positive" }
        require(densityDpi > 0) { "Slot density must be positive" }
    }

    companion object {
        /**
         * Keeps the default display's portrait width and density, independent of main rotation.
         */
        fun matchingViewport(context: Context, viewportWidth: Int, viewportHeight: Int): SlotGeometry {
            val displayMetrics = context.resources.displayMetrics
            require(displayMetrics.widthPixels > 0 && displayMetrics.heightPixels > 0) {
                "Default display dimensions must be usable"
            }
            return forViewport(minOf(displayMetrics.widthPixels, displayMetrics.heightPixels),
                displayMetrics.densityDpi, viewportWidth, viewportHeight)
        }

        fun forViewport(sourceWidth: Int, densityDpi: Int, viewportWidth: Int, viewportHeight: Int): SlotGeometry {
            require(sourceWidth > 0 && viewportWidth > 0 && viewportHeight > 0)
            return SlotGeometry(sourceWidth,
                (sourceWidth.toDouble() * viewportHeight / viewportWidth).roundToInt().coerceAtLeast(1),
                densityDpi)
        }
    }
}
