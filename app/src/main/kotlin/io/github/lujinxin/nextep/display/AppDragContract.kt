package io.github.lujinxin.nextep.display

import android.content.ClipData
import android.content.ClipDescription
import android.content.ComponentName
import android.content.Intent

object AppDragContract {
    const val MIME_TYPE = "application/vnd.io.github.lujinxin.nextep.launcher-activity"

    fun accepts(description: ClipDescription?): Boolean =
        description?.hasMimeType(MIME_TYPE) == true

    fun readLaunchIntent(clipData: ClipData?): Intent? {
        val component = clipData
            ?.takeIf { accepts(it.description) && it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
            ?.let(ComponentName::unflattenFromString)
            ?: return null
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
