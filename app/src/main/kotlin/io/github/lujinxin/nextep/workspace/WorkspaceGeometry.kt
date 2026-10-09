package io.github.lujinxin.nextep.workspace

import kotlin.math.roundToInt

data class WorkspaceGeometry(
    val screenWidth: Int,
    val screenHeight: Int,
    val topHeight: Int,
    val sidebarWidth: Int,
    val sidebarSide: SidebarSide,
) {
    val isLandscape: Boolean get() = screenWidth > screenHeight

    // Control-strip thickness and slot-rail thickness use portrait logical axes.
    val controlWidth: Int get() = if (isLandscape) topHeight else screenWidth
    val controlHeight: Int get() = if (isLandscape) screenHeight else topHeight
    val controlLeft: Int get() = if (isLandscape) screenWidth - topHeight else 0
    val sidebarLeft: Int get() = if (isLandscape || sidebarSide == SidebarSide.LEFT) 0 else contentRight
    val sidebarTop: Int get() = if (!isLandscape) topHeight else
        if (sidebarSide == SidebarSide.RIGHT) 0 else screenHeight - sidebarWidth
    val sidebarPhysicalWidth: Int get() = if (isLandscape) contentWidth else sidebarWidth
    val sidebarPhysicalHeight: Int get() = if (isLandscape) sidebarWidth else contentHeight
    val sidebarLogicalHeight: Int get() = if (isLandscape) contentWidth else contentHeight

    val contentTop: Int get() = if (!isLandscape) topHeight else
        if (sidebarSide == SidebarSide.RIGHT) sidebarWidth else 0
    val contentBottom: Int get() = contentTop + contentHeight

    val contentWidth: Int
        get() = screenWidth - if (isLandscape) topHeight else sidebarWidth

    val contentHeight: Int
        get() = screenHeight - if (isLandscape) sidebarWidth else topHeight

    val contentLeft: Int
        get() = if (!isLandscape && sidebarSide == SidebarSide.LEFT) sidebarWidth else 0

    val contentRight: Int
        get() = contentLeft + contentWidth

    companion object {
        const val TOP_HEIGHT_FRACTION = 0.265f
        const val SIDEBAR_WIDTH_FRACTION = 0.267f

        fun forDisplay(
            screenWidth: Int,
            screenHeight: Int,
            sidebarSide: SidebarSide = SidebarSide.RIGHT,
            density: Float,
        ): WorkspaceGeometry {
            require(screenWidth > 1 && screenHeight > 1) { "Display dimensions must be usable" }
            require(density.isFinite() && density > 0f) { "Display density must be positive and finite" }
            val longSide = maxOf(screenWidth, screenHeight)
            val shortSide = minOf(screenWidth, screenHeight)
            val proportionalTopHeight = (longSide * TOP_HEIGHT_FRACTION).roundToInt()
            // Fixed-size controls must not leave a growing empty band on tablets.
            // Use the same short-side check in either orientation and in every host.
            val topHeight = if (shortSide / density >= WorkspaceControlMetrics.LARGE_SCREEN_MIN_SHORT_SIDE_DP) {
                minOf(proportionalTopHeight, (WorkspaceControlMetrics.COMPACT_HEIGHT_DP * density).roundToInt())
            } else proportionalTopHeight
            return WorkspaceGeometry(
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                topHeight = topHeight.coerceIn(1, longSide - 1),
                sidebarWidth = (shortSide * SIDEBAR_WIDTH_FRACTION)
                    .roundToInt()
                    .coerceIn(1, shortSide - 1),
                sidebarSide = sidebarSide,
            )
        }
    }
}
