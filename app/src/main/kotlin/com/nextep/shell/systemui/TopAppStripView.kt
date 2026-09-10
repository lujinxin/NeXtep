package com.nextep.shell.systemui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nextep.shell.display.AppDragContract
import com.nextep.shell.config.WorkspaceConfigClient
import com.nextep.shell.logging.NeXtepLog
import com.nextep.shell.workspace.SidebarSide
import java.lang.reflect.Proxy

class TopAppStripView(
    context: Context,
    initialSidebarSide: SidebarSide,
    private val onAppClicked: (Intent) -> Unit,
    private val onSidebarSideRequested: (SidebarSide) -> Unit,
    private val onSettingsRequested: () -> Unit,
    private val onExitRequested: () -> Unit,
) : FrameLayout(context) {
    private val repository = TopAppRepository(context)
    private val mediaControl = MediaControlView(context)
    private val titleView = TextView(context).apply {
        text = "NeXtep"
        textSize = 16f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        maxLines = 1
    }
    private val appRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), 0, dp(6), 0)
    }
    private val appScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(appRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }
    private val leftButton = SideSelectionButton(context, SidebarSide.LEFT).apply {
        contentDescription = "小窗靠左"
        setOnClickListener { onSidebarSideRequested(SidebarSide.LEFT) }
    }
    private val rightButton = SideSelectionButton(context, SidebarSide.RIGHT).apply {
        contentDescription = "小窗靠右"
        setOnClickListener { onSidebarSideRequested(SidebarSide.RIGHT) }
    }
    private var sidebarSide = initialSidebarSide
    private val exitButton = ExitButton(context).apply {
        contentDescription = "退出小窗模式"
        setOnClickListener { onExitRequested() }
    }
    private val dividerPaint = Paint().apply { color = Color.argb(90, 255, 255, 255) }
    private var loading = false
    private var lastRefreshAt = 0L
    private var refreshGeneration = 0
    private var touchableInsetsListener: Any? = null

    init {
        setBackgroundColor(Color.TRANSPARENT)
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(7), 0, dp(7), dp(3))
                addView(
                    mediaControl,
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(MEDIA_HEIGHT_DP)),
                )
                addView(
                    actionRow(),
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(ACTION_HEIGHT_DP)),
                )
                addView(
                    appScroll,
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                topMargin = dp(TITLE_HEIGHT_DP)
            },
        )
        updateSideButtons()
        installTouchableStatusBarPassThrough()
        post { SystemUiStatusBarGestureInstaller.install(this) }
    }

    fun setWorkspaceVisible(visible: Boolean) {
        mediaControl.setWorkspaceVisible(visible)
    }

    fun setSidebarSide(side: SidebarSide) {
        sidebarSide = side
        updateSideButtons()
        exitButton.scaleX = if (side == SidebarSide.LEFT) 1f else -1f
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val thickness = resources.displayMetrics.density
        canvas.drawRect(0f, height - thickness, width.toFloat(), height.toFloat(), dividerPaint)
    }

    fun refresh(force: Boolean = false) {
        mediaControl.refresh()
        val now = System.currentTimeMillis()
        if (loading || (!force && now - lastRefreshAt < REFRESH_INTERVAL_MS)) return
        loading = true
        val generation = ++refreshGeneration
        Thread({
            val config = WorkspaceConfigClient.query(context)
            val result = runCatching {
                repository.load(
                    if (config.manualAppOrder) config.appComponents else emptyList(),
                )
            }
            post {
                if (generation != refreshGeneration) return@post
                loading = false
                titleView.text = config.title
                result.onSuccess { entries ->
                    lastRefreshAt = System.currentTimeMillis()
                    renderApps(entries)
                }.onFailure { error ->
                    NeXtepLog.error("top_apps", "Unable to load App strip", error)
                    renderApps(emptyList())
                }
            }
        }, "NeXtep-TopApps").start()
    }

    private fun actionRow(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val buttonSize = dp(ACTION_BUTTON_SIZE_DP)
        addView(leftButton, LinearLayout.LayoutParams(buttonSize, buttonSize))
        addView(rightButton, LinearLayout.LayoutParams(buttonSize, buttonSize))
        addView(
            titleView,
            LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(10)
                marginEnd = dp(10)
            },
        )
        addView(
            GearButton(context).apply {
                contentDescription = "打开 NeXtep 设置"
                setOnClickListener { onSettingsRequested() }
            },
            LinearLayout.LayoutParams(buttonSize, buttonSize),
        )
        addView(
            exitButton.apply { scaleX = if (sidebarSide == SidebarSide.LEFT) 1f else -1f },
            LinearLayout.LayoutParams(buttonSize, buttonSize),
        )
    }

    private fun renderApps(entries: List<TopAppRepository.AppEntry>) {
        val scrollX = appScroll.scrollX
        appRow.removeAllViews()
        if (entries.isEmpty()) {
            appRow.addView(messageView("没有可启动的 App"))
        } else {
            entries.forEach { entry -> appRow.addView(appTile(entry)) }
        }
        appScroll.post { appScroll.scrollTo(scrollX, 0) }
        NeXtepLog.info("top_apps", "Rendered ${entries.size} launcher activities")
    }

    private fun appTile(entry: TopAppRepository.AppEntry): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        isClickable = true
        isLongClickable = true
        contentDescription = entry.label
        setPadding(dp(5), 0, dp(5), dp(3))
        addView(
            ImageView(context).apply {
                setImageDrawable(entry.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LinearLayout.LayoutParams(dp(APP_ICON_SIZE_DP), dp(APP_ICON_SIZE_DP)),
        )
        layoutParams = LinearLayout.LayoutParams(dp(APP_TILE_WIDTH_DP), LayoutParams.MATCH_PARENT)
        setOnClickListener { onAppClicked(Intent(entry.launchIntent)) }
        setOnLongClickListener { tile ->
            val clip = AppDragContract.createClip(entry.label, entry.launchIntent)
            val started = tile.startDragAndDrop(
                clip,
                View.DragShadowBuilder(tile),
                null,
                View.DRAG_FLAG_GLOBAL,
            )
            NeXtepLog.info(
                "top_apps",
                "Global drag started=$started component=${entry.launchIntent.component}",
            )
            started
        }
    }

    private fun updateSideButtons() {
        leftButton.isSideSelected = sidebarSide == SidebarSide.LEFT
        rightButton.isSideSelected = sidebarSide == SidebarSide.RIGHT
    }

    private fun installTouchableStatusBarPassThrough() {
        runCatching {
            val listenerClass = Class.forName(
                "android.view.ViewTreeObserver\$OnComputeInternalInsetsListener",
            )
            val listener = Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, method, arguments ->
                if (method.name == "onComputeInternalInsets") {
                    val info = arguments?.firstOrNull() ?: return@newProxyInstance null
                    info.javaClass.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
                        .invoke(info, TOUCHABLE_INSETS_REGION)
                    (info.javaClass.getField("touchableRegion").get(info) as Region).set(
                        0,
                        dp(TITLE_HEIGHT_DP),
                        width,
                        height,
                    )
                }
                null
            }
            viewTreeObserver.javaClass
                .getMethod("addOnComputeInternalInsetsListener", listenerClass)
                .invoke(viewTreeObserver, listener)
            touchableInsetsListener = listener
        }.onFailure { error ->
            NeXtepLog.warn(
                "top_apps",
                "Unable to pass the status band through to SystemUI",
                error,
            )
        }
    }

    private fun messageView(message: String) = TextView(context).apply {
        text = message
        setTextColor(Color.LTGRAY)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(16), 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TITLE_HEIGHT_DP = 40
        const val MEDIA_HEIGHT_DP = 68
        const val ACTION_HEIGHT_DP = 48
        const val APP_ICON_SIZE_DP = 40
        const val APP_TILE_WIDTH_DP = 50
        const val ACTION_BUTTON_SIZE_DP = 40
        const val REFRESH_INTERVAL_MS = 15_000L
        const val TOUCHABLE_INSETS_REGION = 3
    }
}

private class SideSelectionButton(
    context: Context,
    private val representedSide: SidebarSide,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.15f)
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
    }
    var isSideSelected: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    init { isClickable = true }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val iconWidth = minOf(dp(24f), width * 0.6f)
        val iconHeight = iconWidth
        val iconLeft = (width - iconWidth) / 2f
        val iconTop = (height - iconHeight) / 2f
        val scale = iconWidth / 24f
        val strokeInset = paint.strokeWidth / 2f
        fun rect(left: Float, top: Float, right: Float, bottom: Float) = android.graphics.RectF(
            iconLeft + left * scale + strokeInset,
            iconTop + top * scale + strokeInset,
            iconLeft + right * scale - strokeInset,
            iconTop + bottom * scale - strokeInset,
        )
        val mainRect = if (representedSide == SidebarSide.LEFT) {
            rect(8.5f, 1f, 22f, 23f)
        } else {
            rect(2f, 1f, 15.5f, 23f)
        }
        val stackLeft = if (representedSide == SidebarSide.LEFT) {
            2f
        } else {
            17f
        }
        paint.color = Color.WHITE
        paint.alpha = if (isSideSelected) 255 else 150
        paint.style = if (isSideSelected) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        canvas.drawRect(mainRect, paint)
        repeat(3) { index ->
            val top = 1f + index * 8f
            canvas.drawRect(rect(stackLeft, top, stackLeft + 5f, top + 6f), paint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

private abstract class ActionGlyphButton(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init { isClickable = true }

    final override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGlyph(canvas, paint, width / 2f, height / 2f, minOf(width, height) * 0.28f)
    }

    abstract fun drawGlyph(canvas: Canvas, paint: Paint, cx: Float, cy: Float, radius: Float)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

private class GearButton(context: Context) : ActionGlyphButton(context) {
    override fun drawGlyph(canvas: Canvas, paint: Paint, cx: Float, cy: Float, radius: Float) {
        val gearRadius = radius * 0.82f
        paint.style = Paint.Style.FILL
        repeat(8) { index ->
            canvas.save()
            canvas.rotate(index * 45f, cx, cy)
            canvas.drawRoundRect(
                cx - gearRadius * 0.17f,
                cy - gearRadius,
                cx + gearRadius * 0.17f,
                cy - gearRadius * 0.56f,
                gearRadius * 0.08f,
                gearRadius * 0.08f,
                paint,
            )
            canvas.restore()
        }
        canvas.drawCircle(cx, cy, gearRadius * 0.68f, paint)
        paint.color = Color.rgb(18, 54, 82)
        canvas.drawCircle(cx, cy, gearRadius * 0.28f, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
    }
}

private class ExitButton(context: Context) : ActionGlyphButton(context) {
    override fun drawGlyph(canvas: Canvas, paint: Paint, cx: Float, cy: Float, radius: Float) {
        val scale = radius * 1.15f / 12f
        fun x(value: Float) = cx + (value - 12f) * scale
        fun y(value: Float) = cy + (value - 12f) * scale
        paint.strokeWidth = resources.displayMetrics.density * 1.35f

        val box = android.graphics.Path().apply {
            moveTo(x(9f), y(7f))
            lineTo(x(5f), y(7f))
            lineTo(x(5f), y(21f))
            lineTo(x(17f), y(21f))
            lineTo(x(17f), y(15f))
        }
        canvas.drawPath(box, paint)

        val arrow = android.graphics.Path().apply {
            moveTo(x(8f), y(14f))
            lineTo(x(11f), y(17f))
            lineTo(x(17f), y(11f))
            lineTo(x(19f), y(13f))
            lineTo(x(21f), y(3f))
            lineTo(x(11f), y(5f))
            lineTo(x(13f), y(7f))
            close()
        }
        canvas.drawPath(arrow, paint)
    }
}
