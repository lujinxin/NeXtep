package io.github.lujinxin.nextep.display

import android.content.Context

/**
 * Full-resolution portrait canvas for a preview slot. Density and physical resolution
 * match the phone, but rotating a main-window video must not rotate preview apps.
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
         * Uses the default display resolution in portrait order, independent of main rotation.
         */
        fun matchingDefaultDisplay(context: Context): SlotGeometry {
            val displayMetrics = context.resources.displayMetrics
            require(displayMetrics.widthPixels > 0 && displayMetrics.heightPixels > 0) {
                "Default display dimensions must be usable"
            }
            return SlotGeometry(
                width = minOf(displayMetrics.widthPixels, displayMetrics.heightPixels),
                height = maxOf(displayMetrics.widthPixels, displayMetrics.heightPixels),
                densityDpi = displayMetrics.densityDpi,
            )
        }
    }
}
