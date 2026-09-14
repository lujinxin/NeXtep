package io.github.lujinxin.nextep.display

import android.content.Context

/**
 * Render geometry for a slot VirtualDisplay.
 *
 * The slot canvas must present the SAME configuration (resolution, density, logical size)
 * as the physical Display 0 so that migrating a task between a slot and the main area
 * does not change the task's Configuration. A changed density/size/touch configuration
 * forces Android to restart the Activity (`ActivityTaskManager: Checking to restart`,
 * `changed=0x5008`) for apps that do not declare the matching `configChanges`. Keeping the
 * slot canvas equal to Display 0 makes the migration seamless.
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
         * Builds the slot geometry from the default (Display 0) metrics so a task on the
         * slot sees exactly the same configuration it sees in the main area.
         */
        fun matchingDefaultDisplay(context: Context): SlotGeometry {
            val displayMetrics = context.resources.displayMetrics
            require(displayMetrics.widthPixels > 0 && displayMetrics.heightPixels > 0) {
                "Default display dimensions must be usable"
            }
            return SlotGeometry(
                width = displayMetrics.widthPixels,
                height = displayMetrics.heightPixels,
                densityDpi = displayMetrics.densityDpi,
            )
        }
    }
}
