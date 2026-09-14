package io.github.lujinxin.nextep.systemui

import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry
import java.util.WeakHashMap

/** Layout real dialog windows, so drawing and native touch coordinates stay aligned. */
object SystemDialogLayoutController {
    private data class Layout(val width: Int, val height: Int, val x: Int, val y: Int, val gravity: Int) {
        constructor(p: WindowManager.LayoutParams) : this(p.width, p.height, p.x, p.y, p.gravity)
        fun apply(p: WindowManager.LayoutParams) {
            p.width = width; p.height = height; p.x = x; p.y = y; p.gravity = gravity
        }
    }
    private data class Record(var original: Layout, var applied: Layout? = null)
    private val windows = WeakHashMap<View, Record>()
    private var geometry: WorkspaceGeometry? = null
    private var refreshing = false

    fun beforeLayout(view: View, params: WindowManager.LayoutParams) {
        if (refreshing || view.display?.displayId?.let { it != Display.DEFAULT_DISPLAY } == true) return
        if (!view.javaClass.name.endsWith("DecorView") ||
            params.type !in setOf(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG) ||
            params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND == 0) return
        val incoming = Layout(params)
        val record = windows.getOrPut(view) { Record(incoming) }
        if (incoming != record.applied) record.original = incoming
        record.original.apply(params)
        geometry?.let { fit(params, it) }
        record.applied = Layout(params)
    }

    fun setGeometry(value: WorkspaceGeometry?) {
        geometry = value
        windows.entries.toList().forEach { (view, record) ->
            if (!view.isAttachedToWindow) return@forEach
            val current = view.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            val params = WindowManager.LayoutParams().also { it.copyFrom(current) }
            record.original.apply(params)
            value?.let { fit(params, it) }
            runCatching {
                refreshing = true
                view.context.getSystemService(WindowManager::class.java).updateViewLayout(view, params)
                record.applied = Layout(params)
            }.onFailure { NeXtepLog.warn("system_dialog_layout", "Dialog relayout failed", it) }
            refreshing = false
        }
    }

    private fun fit(p: WindowManager.LayoutParams, g: WorkspaceGeometry) {
        p.width = if (p.width > 0) p.width.coerceAtMost(g.contentWidth) else g.contentWidth
        if (p.height == WindowManager.LayoutParams.MATCH_PARENT || p.height > g.contentHeight) {
            p.height = g.contentHeight
        }
        val vertical = p.gravity and Gravity.VERTICAL_GRAVITY_MASK
        p.gravity = Gravity.LEFT or vertical
        p.x = g.contentLeft + (g.contentWidth - p.width) / 2
        p.y = when (vertical) {
            Gravity.BOTTOM -> p.y.coerceAtLeast(0)
            Gravity.CENTER_VERTICAL -> g.topHeight / 2
            else -> g.topHeight + p.y.coerceAtLeast(0)
        }
    }
}
