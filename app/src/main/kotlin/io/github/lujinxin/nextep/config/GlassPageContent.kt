package io.github.lujinxin.nextep.config

import android.content.Context
import android.graphics.Canvas
import android.graphics.RenderNode
import android.widget.FrameLayout

/** The page and the glass share one recording of the scrolling view hierarchy. */
internal class GlassPageContent(context: Context) : FrameLayout(context) {
    val backdrop = RenderNode("NeXtep-ConfigurationPages")

    override fun dispatchDraw(canvas: Canvas) {
        if (!canvas.isHardwareAccelerated) {
            super.dispatchDraw(canvas)
            return
        }
        backdrop.setPosition(0, 0, width, height)
        val recording = backdrop.beginRecording(width, height)
        try {
            // Draw children only during their normal traversal. Calling View.draw()
            // again to capture glass can consume invalidation and scroll/stretch state.
            super.dispatchDraw(recording)
        } finally {
            backdrop.endRecording()
        }
        canvas.drawRenderNode(backdrop)
    }

    override fun onDetachedFromWindow() {
        backdrop.discardDisplayList()
        super.onDetachedFromWindow()
    }
}
