package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import io.github.lujinxin.nextep.logging.NeXtepLog

/** Rotate panel content as a view subtree so Android also transforms touch and drag events. */
internal class WorkspacePanelHost(context: Context, content: View) : ViewGroup(context) {
    private val projection = WorkspacePanelProjection()
    private var frame = WorkspacePanelProjection.Frame(0, 0, 0f)
    @Volatile private var rotationFrames: List<WorkspacePanelProjection.Frame> = emptyList()

    fun setRotationFrames(frames: List<WorkspacePanelProjection.Frame>) {
        rotationFrames = frames.toList()
    }

    /** Shell publishes immutable drawing coordinates; no View operations occur here. */
    fun stageRotation(rotation: Int) {
        rotationFrames.getOrNull(rotation)?.let { projection.request(it.width, it.height, it.rotation) }
    }

    fun isProjectionReady(): Boolean = projection.canDraw(width, height)

    fun setProjection(rotation: Float, width: Int, height: Int) {
        if (projection.request(width, height, rotation)) requestLayout()
    }

    fun setPanelBackground(drawable: Drawable) {
        // The background uses the same logical axes and render node as the
        // controls. It must not switch independently of their replacement buffer.
        getChildAt(0).background = drawable
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // ViewRoot delivers the window's configuration before drawing its new
        // buffer. Shell and ACTION_CONFIGURATION_CHANGED can arrive after that
        // draw, especially for equal-size 90 <-> 270 degree changes.
        runCatching {
            val window = Configuration::class.java.getField("windowConfiguration").get(newConfig)
            val rotation = window.javaClass.getMethod("getDisplayRotation").invoke(window) as Int
            stageRotation(rotation)
            requestLayout()
        }.onFailure {
            NeXtepLog.warn("workspace_panel_rotation", "Window rotation configuration unavailable", it)
        }
        super.onConfigurationChanged(newConfig)
    }

    init {
        addView(content)
        // Keep the previous buffer if WM supplies an intermediate window size.
        viewTreeObserver.addOnPreDrawListener { projection.canDraw(width, height) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        frame = projection.measure(width, height)
        getChildAt(0).measure(
            MeasureSpec.makeMeasureSpec(frame.logicalWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(frame.logicalHeight, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        getChildAt(0).apply {
            pivotX = 0f
            pivotY = 0f
            rotation = frame.rotation
            translationX = if (frame.rotation > 0f) frame.width.toFloat() else 0f
            translationY = if (frame.rotation < 0f) frame.height.toFloat() else 0f
            // The video host must use this exact drawing frame, including native
            // window configurations that arrive before the global config callback.
            (this as? WorkspacePanelRotationContent)?.onPanelRotationChanged(frame.rotation)
            layout(0, 0, measuredWidth, measuredHeight)
        }
    }
}

internal interface WorkspacePanelRotationContent {
    fun onPanelRotationChanged(rotationDegrees: Float)
}
