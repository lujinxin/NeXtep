package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.content.Intent
import io.github.lujinxin.nextep.config.TopContentMode
import io.github.lujinxin.nextep.config.WorkspaceTopConfig
import android.text.format.DateFormat
import java.util.Date
import java.util.Locale
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.lujinxin.nextep.config.WorkspaceConfigClient
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.SidebarSide
import io.github.lujinxin.nextep.workspace.WorkspaceControlMetrics
import java.lang.reflect.Proxy

class TopAppStripView(
    context: Context,
    initialSidebarSide: SidebarSide,
    private val onAppClicked: (Intent) -> Unit,
    private val onMediaClicked: (android.media.session.MediaController) -> Unit,
    private val onSidebarSideRequested: (SidebarSide) -> Unit,
    private val onSettingsRequested: () -> Unit,
    private val onExitRequested: () -> Unit,
    private val onFrostStrengthChanged: (Int) -> Unit,
    private val onAppDragStarting: (View, Intent) -> Boolean,
    private val onAppDragTouch: (View, android.view.MotionEvent) -> Boolean,
) : FrameLayout(context) {
    private var landscape = false

    fun setLandscape(value: Boolean) {
        landscape = value
        leftButton.contentDescription = if (value) "小窗靠下" else "小窗靠左"
        rightButton.contentDescription = if (value) "小窗靠上" else "小窗靠右"
        // Match the rail placement after the clockwise panel transform.
        leftButton.scaleX = if (value) -1f else 1f
        rightButton.scaleX = if (value) -1f else 1f
    }

    private val repository = TopAppRepository(context)
    private val mediaControl = MediaControlView(context, onMediaClicked)
    private val titleView = LoopingTitleView(context).apply {
        visibility = View.GONE
        textSize = 16f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        maxLines = 1
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val titleIcon = ImageView(context).apply {
        contentDescription = "NeXtep"
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
        }
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        runCatching {
            val packageName = "io.github.lujinxin.nextep"
            val moduleResources = context.packageManager.getResourcesForApplication(packageName)
            val iconId = moduleResources.getIdentifier("ic_launcher_artwork", "drawable", packageName)
            val artwork = android.graphics.BitmapFactory.decodeResource(moduleResources, iconId)
            // The original circular artwork has a one-sixth margin on each side.
            // Use that artwork, rather than masking the square adaptive launcher icon.
            val roundArtwork = android.graphics.Bitmap.createBitmap(
                artwork, artwork.width / 6, artwork.height / 6,
                artwork.width * 2 / 3, artwork.height * 2 / 3,
            )
            setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, roundArtwork))
        }.onFailure { error ->
            NeXtepLog.warn("top_apps", "Unable to load default title icon", error)
            setImageDrawable(context.packageManager.getDefaultActivityIcon())
        }
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
    private var refreshPending = false
    private var lastRefreshAt = 0L
    private var refreshGeneration = 0
    private var touchableInsetsListener: Any? = null
    private var internalAppDragActive = false
    private var deferredApps: List<TopAppRepository.AppEntry>? = null
    private val dismissHint = TextView(context).apply {
        text = "松手移到后台"
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.argb(230, 28, 77, 90))
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(7), 0, dp(7), dp(WorkspaceControlMetrics.CONTENT_BOTTOM_PADDING_DP))
                addView(
                    mediaControl,
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(WorkspaceControlMetrics.MEDIA_HEIGHT_DP)),
                )
                addView(
                    actionRow(),
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(WorkspaceControlMetrics.ACTION_HEIGHT_DP)),
                )
                addView(
                    appScroll,
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                topMargin = dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP)
            },
        )
        addView(dismissHint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP)
        })
        updateSideButtons()
        installTouchableStatusBarPassThrough()
        post { SystemUiStatusBarGestureInstaller.install(this) }
    }

    private val configChangedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Read configuration through the explicit module receiver; do not trust broadcast extras.
            if (intent.action == io.github.lujinxin.nextep.config.WorkspaceConfigContract.ACTION_CHANGED) {
                refresh(force = true)
            }
        }
    }
    private var topConfig = WorkspaceTopConfig()
    private var workspaceVisible = false
    private val titleTick = object : Runnable {
        override fun run() {
            renderTitle()
            if (isAttachedToWindow && workspaceVisible &&
                topConfig.contentMode in listOf(TopContentMode.TIME, TopContentMode.DATE)) {
                postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
            }
        }
    }

    private fun updateTitle() {
        removeCallbacks(titleTick)
        titleTick.run()
    }

    private fun renderTitle() {
        val mode = topConfig.contentMode
        val custom = mode == TopContentMode.TEXT
        val scroll = custom && topConfig.textScroll
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        titleView.setScrolling(scroll, workspaceVisible)
        titleView.gravity = Gravity.CENTER
        titleView.textSize = if (custom) topConfig.textSizeSp.toFloat() else 16f
        titleView.typeface = if (custom) io.github.lujinxin.nextep.config.TopTextStyle.typeface(
            topConfig.textFontFamily, topConfig.textBold,
        ) else android.graphics.Typeface.DEFAULT
        titleIcon.visibility = if (mode == TopContentMode.ICON) View.VISIBLE else View.GONE
        titleView.visibility = if (mode == TopContentMode.ICON || mode == TopContentMode.EMPTY) View.GONE else View.VISIBLE
        val displayTitle = when (mode) {
            TopContentMode.TIME -> {
                val skeleton = if (DateFormat.is24HourFormat(context)) {
                    if (topConfig.showSeconds) "Hms" else "Hm"
                } else if (topConfig.showSeconds) "hms" else "hm"
                DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton), Date())
            }
            TopContentMode.DATE -> {
                val now = Date()
                val date = DateFormat.format(
                    DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMd"), now)
                val weekday = DateFormat.format("EEE", now)
                "$date $weekday"
            }
            else -> topConfig.title
        }
        // App-strip refreshes must not restart a long marquee before its tail
        // becomes visible. Only changing the content should reset its position.
        if (!android.text.TextUtils.equals(titleView.text, displayTitle)) titleView.text = displayTitle
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(configChangedReceiver,
            android.content.IntentFilter(io.github.lujinxin.nextep.config.WorkspaceConfigContract.ACTION_CHANGED),
            Context.RECEIVER_EXPORTED)
        updateTitle()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(titleTick)
        context.unregisterReceiver(configChangedReceiver)
        super.onDetachedFromWindow()
    }

    fun setWorkspaceVisible(visible: Boolean) {
        workspaceVisible = visible
        updateTitle()
        mediaControl.setWorkspaceVisible(visible)
    }

    fun setSidebarSide(side: SidebarSide) {
        sidebarSide = side
        updateSideButtons()
        exitButton.scaleX = if (side == SidebarSide.LEFT) -1f else 1f
    }

    fun setDismissTargetHighlighted(highlighted: Boolean) {
        dismissHint.visibility = if (highlighted) VISIBLE else GONE
    }

    fun finishInternalAppDrag() {
        internalAppDragActive = false
        deferredApps?.let { entries -> deferredApps = null; renderApps(entries) }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val thickness = resources.displayMetrics.density
        canvas.drawRect(0f, height - thickness, width.toFloat(), height.toFloat(), dividerPaint)
    }

    fun refresh(force: Boolean = false) {
        mediaControl.refresh()
        val now = System.currentTimeMillis()
        if (loading) {
            refreshPending = refreshPending || force
            return
        }
        if (!force && now - lastRefreshAt < REFRESH_INTERVAL_MS) return
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
                onFrostStrengthChanged(config.frostStrength)
                topConfig = config
                updateTitle()
                result.onSuccess { entries ->
                    lastRefreshAt = System.currentTimeMillis()
                    renderApps(entries)
                }.onFailure { error ->
                    NeXtepLog.error("top_apps", "Unable to load App strip", error)
                    renderApps(emptyList())
                }
                if (refreshPending) {
                    refreshPending = false
                    refresh(force = true)
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
            FrameLayout(context).apply {
                addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                addView(titleIcon, LayoutParams(dp(32), dp(32), Gravity.CENTER))
            },
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
            exitButton.apply { scaleX = if (sidebarSide == SidebarSide.LEFT) -1f else 1f },
            LinearLayout.LayoutParams(buttonSize, buttonSize),
        )
    }

    private fun renderApps(entries: List<TopAppRepository.AppEntry>) {
        // Keep the touch-owning tile attached until its drag finishes.
        if (internalAppDragActive) { deferredApps = entries; return }
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
        setPadding(dp(5), 0, dp(5), dp(WorkspaceControlMetrics.APP_BOTTOM_PADDING_DP))
        addView(
            ImageView(context).apply {
                setImageDrawable(entry.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LinearLayout.LayoutParams(dp(WorkspaceControlMetrics.APP_ICON_SIZE_DP), dp(WorkspaceControlMetrics.APP_ICON_SIZE_DP)),
        )
        layoutParams = LinearLayout.LayoutParams(dp(APP_TILE_WIDTH_DP), LayoutParams.MATCH_PARENT)
        setOnClickListener { onAppClicked(Intent(entry.launchIntent)) }
        setOnLongClickListener { tile ->
            val started = onAppDragStarting(tile, Intent(entry.launchIntent))
            internalAppDragActive = started
            NeXtepLog.info(
                "top_apps",
                "Internal drag started=$started component=${entry.launchIntent.component}",
            )
            started
        }
        setOnTouchListener { tile, event -> onAppDragTouch(tile, event) }
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
                    val region = info.javaClass.getField("touchableRegion").get(info) as Region
                    if (landscape) {
                        // Window-local coordinates after the panel's clockwise rotation.
                        region.set(0, 0, (height - dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP)).coerceAtLeast(0), width)
                    } else {
                        region.set(0, dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP), width, height)
                    }
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
