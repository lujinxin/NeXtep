package io.github.lujinxin.nextep.systemui

import android.content.Context
import io.github.lujinxin.nextep.logging.NeXtepLog

object SystemUiPanelCollapser {
    fun collapse(context: Context) {
        runCatching {
            val manager = context.getSystemService("statusbar")
                ?: error("StatusBarManager unavailable")
            manager.javaClass.getMethod("collapsePanels").invoke(manager)
        }.onFailure { error ->
            NeXtepLog.warn("systemui_panel", "Unable to collapse SystemUI panels", error)
        }
    }
}
