package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.WindowManager
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.SidebarSide
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry
import io.github.lujinxin.nextep.workspace.WorkspaceNavigationInsets

/** SystemUI owns the physical viewport; other processes receive its geometry. */
internal class WorkspaceViewport(
    private val context: Context,
    private val onChanged: () -> Unit,
) {
    private data class Snapshot(
        val width: Int,
        val height: Int,
        val density: Float,
        val navigationInsets: WorkspaceNavigationInsets,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var cached: Snapshot? = null
    private val notifyChanged = Runnable { onChanged() }
    private val refreshChanged = Runnable {
        val previous = cached
        refresh()
        if (previous != cached) notifyGeometryChanged()
    }

    fun geometry(side: SidebarSide, refreshMetrics: Boolean = false): WorkspaceGeometry {
        if (refreshMetrics || cached == null) refresh()
        val viewport = checkNotNull(cached)
        return WorkspaceGeometry.forDisplay(
            viewport.width, viewport.height, side, viewport.density, viewport.navigationInsets,
        )
    }

    private fun refresh() {
        val metrics = context.resources.displayMetrics
        runCatching {
            // This is a display-wide SystemUI canvas, not an Activity's app bounds.
            // WindowMetrics bounds include the navigation area; subtract it once below.
            val window = context.getSystemService(WindowManager::class.java).maximumWindowMetrics
            val bounds = window.bounds
            check(bounds.width() > 1 && bounds.height() > 1)
            cached = Snapshot(bounds.width(), bounds.height(), metrics.density,
                navigationInsets(window.windowInsets))
        }.onFailure { error ->
            NeXtepLog.warn("workspace_viewport", "Window metrics unavailable; retaining known navigation insets", error)
            val previous = cached
            val retainedInsets = if (previous != null &&
                previous.width == metrics.widthPixels && previous.height == metrics.heightPixels
            ) previous.navigationInsets else WorkspaceNavigationInsets()
            cached = Snapshot(metrics.widthPixels, metrics.heightPixels, metrics.density,
                retainedInsets)
        }
    }

    /** Only the transparent, full-display window can report display-wide insets.
     * A sidebar already shortened above the bar would report a zero bottom inset. */
    fun onFullWindowInsets(insets: WindowInsets) {
        val previous = cached ?: return
        val frame = insets.frame
        if (frame.width != previous.width || frame.height != previous.height) {
            // Rotation/configuration can reach ViewRoot before SystemUI resources settle.
            handler.removeCallbacks(refreshChanged)
            handler.post(refreshChanged)
            return
        }
        val updated = previous.copy(navigationInsets = navigationInsets(insets))
        if (updated == previous) return
        cached = updated
        notifyGeometryChanged()
    }

    private fun notifyGeometryChanged() {
        // Avoid relayout inside inset dispatch, and merge simultaneous updates.
        handler.removeCallbacks(notifyChanged)
        handler.post(notifyChanged)
    }

    private fun navigationInsets(insets: WindowInsets): WorkspaceNavigationInsets {
        // Reserve stable navigation space even if a full-screen app hides the bar.
        // IME/status/side-back gestures must not resize the workspace on every change.
        val navigation = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
        return WorkspaceNavigationInsets(navigation.left, navigation.top, navigation.right, navigation.bottom)
    }
}
