package io.github.lujinxin.nextep.workspace

import kotlin.math.roundToInt

data class WorkspaceNavigationInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

data class WorkspaceGeometry(
    val screenWidth: Int,
    val screenHeight: Int,
    val topHeight: Int,
    val sidebarWidth: Int,
    val sidebarSide: SidebarSide,
    val navigationInsets: WorkspaceNavigationInsets = WorkspaceNavigationInsets(),
) {
    val isLandscape: Boolean get() = screenWidth > screenHeight

    // The task still occupies the full display. Its navigation-safe source rectangle
    // is fitted uniformly, so the native navigation padding is not scaled a second time.
    val availableLeft: Int get() = navigationInsets.left
    val availableTop: Int get() = navigationInsets.top
    val availableRight: Int get() = screenWidth - navigationInsets.right
    val availableBottom: Int get() = screenHeight - navigationInsets.bottom
    val availableWidth: Int get() = availableRight - availableLeft
    val availableHeight: Int get() = availableBottom - availableTop

    init {
        require(screenWidth > 1 && screenHeight > 1)
        require(navigationInsets.left >= 0 && navigationInsets.top >= 0 &&
            navigationInsets.right >= 0 && navigationInsets.bottom >= 0)
        require(navigationInsets.left <= screenWidth - 2 &&
            navigationInsets.right <= screenWidth - navigationInsets.left - 2)
        require(navigationInsets.top <= screenHeight - 2 &&
            navigationInsets.bottom <= screenHeight - navigationInsets.top - 2)
        require(availableWidth > 1 && availableHeight > 1)
        require(topHeight in 1 until (if (isLandscape) availableWidth else availableHeight))
        require(sidebarWidth in 1 until (if (isLandscape) availableHeight else availableWidth))
    }

    // Control-strip thickness and slot-rail thickness use portrait logical axes.
    val controlWidth: Int get() = if (isLandscape) topHeight else availableWidth
    val controlHeight: Int get() = if (isLandscape) availableHeight else topHeight
    val controlLeft: Int get() = if (isLandscape) availableRight - topHeight else availableLeft
    val controlTop: Int get() = availableTop
    val sidebarLeft: Int get() = if (isLandscape || sidebarSide == SidebarSide.LEFT) availableLeft else contentRight
    val sidebarTop: Int get() = if (!isLandscape) availableTop + topHeight else
        if (sidebarSide == SidebarSide.RIGHT) availableTop else availableBottom - sidebarWidth
    val sidebarPhysicalWidth: Int get() = if (isLandscape) contentWidth else sidebarWidth
    val sidebarPhysicalHeight: Int get() = if (isLandscape) sidebarWidth else contentHeight
    val sidebarLogicalHeight: Int get() = if (isLandscape) contentWidth else contentHeight

    val contentTop: Int get() = availableTop + if (!isLandscape) topHeight else
        if (sidebarSide == SidebarSide.RIGHT) sidebarWidth else 0
    val contentBottom: Int get() = contentTop + contentHeight

    val contentWidth: Int
        get() = availableWidth - if (isLandscape) topHeight else sidebarWidth

    val contentHeight: Int
        get() = availableHeight - if (isLandscape) sidebarWidth else topHeight

    val contentLeft: Int
        get() = availableLeft + if (!isLandscape && sidebarSide == SidebarSide.LEFT) sidebarWidth else 0

    val contentRight: Int
        get() = contentLeft + contentWidth

    val contentScale: Float
        get() = minOf(contentWidth.toFloat() / availableWidth, contentHeight.toFloat() / availableHeight)

    val contentTranslationX: Float
        get() = contentLeft + (contentWidth - availableWidth * contentScale) / 2f - availableLeft * contentScale

    val contentTranslationY: Float
        get() = contentTop + (contentHeight - availableHeight * contentScale) / 2f - availableTop * contentScale

    companion object {
        const val TOP_HEIGHT_FRACTION = 0.265f
        const val SIDEBAR_WIDTH_FRACTION = 0.267f

        fun forDisplay(
            screenWidth: Int,
            screenHeight: Int,
            sidebarSide: SidebarSide = SidebarSide.RIGHT,
            density: Float,
            navigationInsets: WorkspaceNavigationInsets = WorkspaceNavigationInsets(),
        ): WorkspaceGeometry {
            require(screenWidth > 1 && screenHeight > 1) { "Display dimensions must be usable" }
            require(density.isFinite() && density > 0f) { "Display density must be positive and finite" }
            val left = navigationInsets.left.coerceIn(0, screenWidth - 2)
            val top = navigationInsets.top.coerceIn(0, screenHeight - 2)
            val safeInsets = WorkspaceNavigationInsets(
                left, top,
                navigationInsets.right.coerceIn(0, screenWidth - left - 2),
                navigationInsets.bottom.coerceIn(0, screenHeight - top - 2),
            )
            val availableWidth = screenWidth - safeInsets.left - safeInsets.right
            val availableHeight = screenHeight - safeInsets.top - safeInsets.bottom
            val landscape = screenWidth > screenHeight
            val longSide = if (landscape) availableWidth else availableHeight
            val shortSide = if (landscape) availableHeight else availableWidth
            // Navigation space comes out of the task area, not fixed-height controls.
            // Shrinking the strip with the usable height can clip its app-icon row.
            val proportionalTopHeight = (maxOf(screenWidth, screenHeight) * TOP_HEIGHT_FRACTION).roundToInt()
            // Fixed-size controls must not leave a growing empty band on tablets.
            // Use the same short-side check in either orientation and in every host.
            val topHeight = if (minOf(screenWidth, screenHeight) / density >= WorkspaceControlMetrics.LARGE_SCREEN_MIN_SHORT_SIDE_DP) {
                minOf(proportionalTopHeight, (WorkspaceControlMetrics.COMPACT_HEIGHT_DP * density).roundToInt())
            } else proportionalTopHeight
            val controlThickness = topHeight.coerceIn(1, longSide - 1)
            // Derive the rail from the remaining main area, rather than independently
            // choosing its width. Both axes must fit the same safe source aspect ratio.
            val mainShortSide = (shortSide.toDouble() * (longSide - controlThickness) / longSide)
                .roundToInt().coerceIn(1, shortSide - 1)
            return WorkspaceGeometry(
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                topHeight = controlThickness,
                sidebarWidth = shortSide - mainShortSide,
                sidebarSide = sidebarSide,
                navigationInsets = safeInsets,
            )
        }
    }
}
