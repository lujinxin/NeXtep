package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.monitoring.WorkspaceMetric
import io.github.lujinxin.nextep.monitoring.WorkspaceMonitorSampler
import java.util.concurrent.Executors

internal class WorkspaceMonitorView(
    context: Context,
    private val slotSnapshot: () -> Bundle,
) : LinearLayout(context) {
    private var positions = emptyList<WorkspaceMetric?>()
    private val cells = mutableMapOf<WorkspaceMetric, ReadingCell>()
    private var workspaceVisible = false
    private var controlsSelected = true
    private var aggregatedVisible = false
    private var foreground = Color.WHITE
    private var labelForeground = Color.rgb(170, 219, 250)
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(64, 224, 240, 255)
        strokeWidth = resources.displayMetrics.density
    }
    private var session: WorkspaceMonitorSampler? = null
    private val tick = Runnable { sample() }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        visibility = GONE
    }

    fun setItems(ids: List<String>) {
        val metrics = WorkspaceMetric.slots(ids).map { WorkspaceMetric.fromId(it) }
        if (positions == metrics) {
            updateLabels()
            return
        }
        stop()
        positions = metrics
        cells.clear()
        removeAllViews()
        for (row in metrics.chunked(WorkspaceMetric.COLUMN_COUNT)) {
            if (row.all { it == null }) continue
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                for (metric in row) {
                    val child = if (metric == null) View(context) else readingCell(metric)
                    addView(child, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
                }
            }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
        updateLabels()
        visibility = if (cells.isEmpty()) GONE else VISIBLE
        updateSampling()
    }

    fun setWorkspaceVisible(visible: Boolean) {
        workspaceVisible = visible
        updateSampling()
    }

    fun setWallpaperLight(light: Boolean) {
        val color = if (light) Color.BLACK else Color.WHITE
        if (foreground == color) return
        foreground = color
        labelForeground = if (light) Color.rgb(20, 83, 110) else Color.rgb(170, 219, 250)
        divider.color = if (light) Color.argb(62, 20, 67, 89) else Color.argb(64, 224, 240, 255)
        cells.values.forEach { it.setColors(labelForeground, color) }
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val inset = 5 * resources.displayMetrics.density
        if (height <= 2 * inset || cells.isEmpty()) return
        for (column in 1 until WorkspaceMetric.COLUMN_COUNT) {
            val x = width * column / WorkspaceMetric.COLUMN_COUNT.toFloat()
            canvas.drawLine(x, inset, x, height - inset, divider)
        }
    }

    fun setControlsSelected(selected: Boolean) {
        if (controlsSelected == selected) return
        controlsSelected = selected
        updateSampling()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        updateSampling()
    }

    override fun onDetachedFromWindow() {
        aggregatedVisible = false
        stop()
        super.onDetachedFromWindow()
    }

    private fun updateSampling() {
        if (!workspaceVisible || !controlsSelected || !aggregatedVisible || cells.isEmpty()) {
            stop()
            return
        }
        if (session != null) return
        session = WorkspaceMonitorSampler(context, cells.keys.toSet(), worker)
        sample(firstSample = true)
    }

    private fun stop() {
        removeCallbacks(tick)
        session = null
    }

    private fun sample(firstSample: Boolean = false) {
        val active = session ?: return
        val slots = if (WorkspaceMetric.SLOTS in cells || WorkspaceMetric.SLOT_FRAMES in cells) slotSnapshot() else null
        worker.execute {
            val readings = runCatching { active.read(slots) }.getOrDefault(emptyMap())
            context.mainExecutor.execute {
                if (session === active) {
                    cells.forEach { (metric, cell) ->
                        val next = readings[metric]
                        // The first read establishes baselines for interval-based metrics.
                        // Retain the displayed reading while waiting for the follow-up.
                        if (next != null || !firstSample) {
                            val value = next ?: unavailable()
                            if (cell.value != value) updateReading(metric, cell, value)
                        }
                    }
                    postDelayed(tick, if (firstSample) FIRST_SAMPLE_FOLLOW_UP_MS else SAMPLE_INTERVAL_MS)
                }
            }
        }
    }

    private fun updateLabels() {
        cells.forEach { (metric, cell) ->
            cell.label = AppLanguage.string(context, metric.compactLabel)
            updateReading(metric, cell, cell.value)
        }
    }

    private fun updateReading(metric: WorkspaceMetric, cell: ReadingCell, value: String) {
        cell.value = value
        val label = AppLanguage.string(context, metric.label)
        cell.contentDescription = "$label, $value"
    }

    private fun readingCell(metric: WorkspaceMetric): View {
        val cell = ReadingCell(context, labelForeground, foreground).apply { value = unavailable() }
        cells[metric] = cell
        return cell
    }

    private fun unavailable() = AppLanguage.string(context, R.string.energy_unavailable)

    private class ReadingCell(context: Context, labelColor: Int, valueColor: Int) : View(context) {
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { color = labelColor }
        private val valuePaint = Paint(labelPaint).apply { color = valueColor }

        var label = ""
            set(text) {
                if (field == text) return
                field = text
                invalidate()
            }

        var value = ""
            set(text) {
                if (field == text) return
                field = text
                invalidate()
            }

        init {
            isScreenReaderFocusable = true
            val inset = (8 * resources.displayMetrics.density).toInt()
            setPaddingRelative(inset, 0, inset, 0)
        }

        fun setColors(labelColor: Int, valueColor: Int) {
            labelPaint.color = labelColor
            valuePaint.color = valueColor
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val left = paddingLeft.toFloat()
            val right = (width - paddingRight).toFloat()
            val availableWidth = right - left - 4 * resources.displayMetrics.density
            if (availableWidth <= 0 || height <= 1 || label.isEmpty()) return

            preparePaint(labelPaint, 10f)
            preparePaint(valuePaint, 12f)
            val textWidth = labelPaint.measureText(label) + valuePaint.measureText(value)
            val scale = if (textWidth > availableWidth) availableWidth / textWidth else 1f
            labelPaint.textScaleX = scale
            valuePaint.textScaleX = scale

            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            labelPaint.textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
            valuePaint.textAlign = if (rtl) Paint.Align.LEFT else Paint.Align.RIGHT
            canvas.drawText(label, if (rtl) right else left, baseline(labelPaint), labelPaint)
            canvas.drawText(value, if (rtl) left else right, baseline(valuePaint), valuePaint)
        }

        private fun preparePaint(paint: Paint, sizeSp: Float) {
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics)
            val lineHeight = paint.descent() - paint.ascent()
            if (lineHeight > height) paint.textSize *= (height - 1f) / lineHeight
            paint.textScaleX = 1f
        }

        private fun baseline(paint: Paint) = (height - paint.ascent() - paint.descent()) / 2f
    }

    companion object {
        private const val FIRST_SAMPLE_FOLLOW_UP_MS = 500L
        private const val SAMPLE_INTERVAL_MS = 2_000L
        private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "NeXtep-workspace-monitor") }
    }
}
