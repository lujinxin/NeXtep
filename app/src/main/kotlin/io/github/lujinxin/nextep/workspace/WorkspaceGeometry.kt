package io.github.lujinxin.nextep.workspace

import kotlin.math.roundToInt

data class WorkspaceGeometry(
    val screenWidth: Int,
    val screenHeight: Int,
    val topHeight: Int,
    val sidebarWidth: Int,
    val sidebarSide: SidebarSide,
) {
    val contentWidth: Int
        get() = screenWidth - sidebarWidth

    val contentHeight: Int
        get() = screenHeight - topHeight

    val contentLeft: Int
        get() = if (sidebarSide == SidebarSide.LEFT) sidebarWidth else 0

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
                topHeight = (screenHeight * TOP_HEIGHT_FRACTION)
                    .roundToInt()
                    .coerceIn(1, screenHeight - 1),
                sidebarWidth = (screenWidth * SIDEBAR_WIDTH_FRACTION)
                    .roundToInt()
                    .coerceIn(1, screenWidth - 1),
                sidebarSide = sidebarSide,
            )
        }
    }
}
