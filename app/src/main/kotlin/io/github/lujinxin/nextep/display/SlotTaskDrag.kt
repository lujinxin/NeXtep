package io.github.lujinxin.nextep.display

import android.content.ClipData
import android.content.ClipDescription

/** Process-local ownership token; never accept arbitrary task IDs from external drag data. */
data class SlotTaskDrag(val slotIndex: Int, val taskId: Int, val displayId: Int) {
    var dropPoint: android.graphics.PointF? = null

    fun clip(): ClipData = ClipData(
        ClipDescription("交换小窗应用", arrayOf(MIME_TYPE)), ClipData.Item(taskId.toString()),
    )

    companion object {
        const val MIME_TYPE = "application/vnd.io.github.lujinxin.nextep.slot-task"
    }
}
