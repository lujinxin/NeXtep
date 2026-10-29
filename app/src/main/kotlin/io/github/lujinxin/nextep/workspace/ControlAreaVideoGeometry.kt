package io.github.lujinxin.nextep.workspace

/** The panel stays in phone axes; the native player's canvas stays in display axes. */
internal object ControlAreaVideoGeometry {
    data class Clip(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val isEmpty: Boolean get() = left >= right || top >= bottom
    }

    data class Viewport(
        val width: Int,
        val height: Int,
        val inverseScaleX: Float,
        val inverseScaleY: Float,
        val clip: Clip,
    )

    data class Placement(val scale: Float, val left: Float, val top: Float)

    fun viewport(width: Int, height: Int, panelRotation: Float, clip: Clip): Viewport {
        require(width > 0 && height > 0)
        require(panelRotation == 0f || panelRotation == -90f || panelRotation == 90f)
        val left = clip.left.coerceIn(0, width)
        val top = clip.top.coerceIn(0, height)
        val right = clip.right.coerceIn(left, width)
        val bottom = clip.bottom.coerceIn(top, height)
        if (panelRotation == 0f) return Viewport(width, height, 1f, 1f, Clip(left, top, right, bottom))

        // SurfaceView submits the axis-aligned bounding box of its rotated View.
        // Cancel its unequal axis scales, without inheriting the panel's rotation.
        val projected = if (panelRotation > 0f) {
            Clip(height - bottom, left, height - top, right)
        } else {
            Clip(top, width - right, bottom, width - left)
        }
        return Viewport(height, width, width.toFloat() / height, height.toFloat() / width, projected)
    }

    fun place(sourceWidth: Int, sourceHeight: Int, width: Int, height: Int, fill: Boolean): Placement {
        require(sourceWidth > 0 && sourceHeight > 0 && width > 0 && height > 0)
        val horizontal = width.toFloat() / sourceWidth
        val vertical = height.toFloat() / sourceHeight
        val scale = if (fill) maxOf(horizontal, vertical) else minOf(horizontal, vertical)
        return Placement(scale, (width - sourceWidth * scale) / 2f, (height - sourceHeight * scale) / 2f)
    }
}
