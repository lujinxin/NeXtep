package io.github.lujinxin.nextep.systemui

/** A window's new rotation and new dimensions arrive on different callbacks. */
internal class WorkspacePanelProjection {
    data class Frame(val width: Int, val height: Int, val rotation: Float) {
        val logicalWidth: Int get() = if (rotation == 0f) width else height
        val logicalHeight: Int get() = if (rotation == 0f) height else width
    }

    @Volatile private var requested: Frame? = null
    @Volatile private var applied: Frame? = null

    fun request(width: Int, height: Int, rotation: Float): Boolean {
        val next = Frame(width, height, rotation)
        val changed = requested != next
        if (changed) requested = next
        // Shell can stage this same target before configureWindow reaches the
        // main looper. A same-size reverse rotation still needs its first layout.
        return changed || applied != next
    }

    fun measure(width: Int, height: Int): Frame {
        requested?.takeIf { it.width == width && it.height == height }?.let { applied = it }
        return applied ?: Frame(width, height, 0f).also { applied = it }
    }

    fun canDraw(width: Int, height: Int): Boolean =
        applied?.let { it == requested && it.width == width && it.height == height } == true
}
