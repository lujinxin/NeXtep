package io.github.lujinxin.nextep.workspace

/** Native control coordinates, separate from the pixels of the borrowed video window. */
internal data class ControlAreaVideoButtonBounds(
    val left: Int, val top: Int, val width: Int, val height: Int,
    val rootWidth: Int, val rootHeight: Int,
) {
    fun encode() = intArrayOf(left, top, width, height, rootWidth, rootHeight)

    companion object {
        fun decode(values: IntArray?): ControlAreaVideoButtonBounds? {
            if (values == null || values.size != 6 || values.any { it !in 0..16384 } ||
                values.drop(2).any { it == 0 } || values[0] + values[2] > values[4] ||
                values[1] + values[3] > values[5]) return null
            return ControlAreaVideoButtonBounds(values[0], values[1], values[2], values[3], values[4], values[5])
        }
    }
}
