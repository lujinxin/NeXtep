package io.github.lujinxin.nextep.workspace

/** Shared dimensions for control content and the workspace region it occupies. */
internal object WorkspaceControlMetrics {
    const val STATUS_HEIGHT_DP = 40
    const val HEADER_HEIGHT_DP = 68
    const val ACTION_HEIGHT_DP = 48
    const val APP_ICON_SIZE_DP = 40
    const val APP_ICON_BOTTOM_INSET_DP = 8
    const val APP_BOTTOM_PADDING_DP = 3
    const val CONTENT_BOTTOM_PADDING_DP = 3
    const val LARGE_SCREEN_MIN_SHORT_SIDE_DP = 600
    const val COMPACT_HEIGHT_DP = STATUS_HEIGHT_DP + HEADER_HEIGHT_DP + ACTION_HEIGHT_DP +
        APP_ICON_SIZE_DP + APP_BOTTOM_PADDING_DP + CONTENT_BOTTOM_PADDING_DP

    fun headerHeightPixels(controlHeight: Int, density: Float): Int {
        fun dp(value: Int) = (value * density).toInt()
        val fixedHeight = dp(STATUS_HEIGHT_DP) + dp(ACTION_HEIGHT_DP) + dp(APP_ICON_SIZE_DP) +
            dp(APP_BOTTOM_PADDING_DP) + dp(CONTENT_BOTTOM_PADDING_DP)
        // Keep status/actions/icons intact when the control area is shorter.
        return minOf(dp(HEADER_HEIGHT_DP), (controlHeight - fixedHeight).coerceAtLeast(1))
    }
}
