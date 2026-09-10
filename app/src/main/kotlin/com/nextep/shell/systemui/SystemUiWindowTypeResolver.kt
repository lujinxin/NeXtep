package com.nextep.shell.systemui

import android.view.WindowManager
import com.nextep.shell.logging.NeXtepLog

object SystemUiWindowTypeResolver {
    private val candidates = listOf(
        "TYPE_STATUS_BAR_SUB_PANEL",
        "TYPE_NAVIGATION_BAR_PANEL",
    )

    fun resolve(): Int? {
        for (candidate in candidates) {
            try {
                val field = WindowManager.LayoutParams::class.java.getDeclaredField(candidate)
                field.isAccessible = true
                val value = field.getInt(null)
                NeXtepLog.info("systemui_window", "Selected window type=$candidate")
                return value
            } catch (error: Throwable) {
                NeXtepLog.debug("systemui_window", "Unavailable window type=$candidate: $error")
            }
        }
        NeXtepLog.warn("systemui_window", "No validated SystemUI window type candidate")
        return null
    }
}
