package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.view.MotionEvent

class SystemUiGestureCoordinator(
    context: Context,
    gestureHeightPx: Float? = null,
) {
    private val detector = TopCornerGestureDetector(
        density = context.resources.displayMetrics.density,
        statusBarHeightPx = gestureHeightPx ?: resolveStatusBarHeight(context),
    )

    /**
     * Observes events without consuming them. A future device-confirmed status-bar hook must send
     * ACTION_CANCEL to the original chain only after this method returns CONFIRMED.
     */
    fun observe(event: MotionEvent, screenWidth: Int): TopCornerGestureDetector.Result {
        if (!SystemUiRuntime.isStatusBarGestureEnabled()) {
            detector.reset()
            return TopCornerGestureDetector.Result.IGNORED
        }
        return detector.observe(event, screenWidth)
    }

    fun dispatchConfirmedToggle() {
        SystemUiRuntime.toggleWorkspaceFromGesture()
    }

    private fun resolveStatusBarHeight(context: Context): Float {
        val resources = context.resources
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId != 0) {
            resources.getDimension(resourceId)
        } else {
            40f * resources.displayMetrics.density
        }
    }
}
