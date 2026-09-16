package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Rotate panel content as a view subtree so Android also transforms touch and drag events. */
internal class WorkspacePanelHost(context: Context, content: View) : ViewGroup(context) {
    var landscape: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    init { addView(content) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        getChildAt(0).measure(
            MeasureSpec.makeMeasureSpec(if (landscape) height else width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(if (landscape) width else height, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        getChildAt(0).apply {
            layout(0, 0, measuredWidth, measuredHeight)
            pivotX = 0f
            pivotY = 0f
            rotation = if (landscape) 90f else 0f
            translationX = if (landscape) this@WorkspacePanelHost.width.toFloat() else 0f
            translationY = 0f
        }
    }
}
