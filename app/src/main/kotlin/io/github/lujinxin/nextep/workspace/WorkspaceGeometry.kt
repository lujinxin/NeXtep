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
        ): WorkspaceGeometry {
            require(screenWidth > 1 && screenHeight > 1) { "Display dimensions must be usable" }
            return WorkspaceGeometry(
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                topHeight = (maxOf(screenWidth, screenHeight) * TOP_HEIGHT_FRACTION)
                    .roundToInt()
                    .coerceIn(1, maxOf(screenWidth, screenHeight) - 1),
                sidebarWidth = (minOf(screenWidth, screenHeight) * SIDEBAR_WIDTH_FRACTION)
                    .roundToInt()
                    .coerceIn(1, minOf(screenWidth, screenHeight) - 1),
                sidebarSide = sidebarSide,
            )
        }
    }
}
