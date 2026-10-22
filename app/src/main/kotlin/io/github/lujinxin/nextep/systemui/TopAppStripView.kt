package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.config.TopContentMode
import io.github.lujinxin.nextep.config.WorkspaceConfigClient
import io.github.lujinxin.nextep.config.WorkspaceTopConfig
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.SidebarButtonLayout
import io.github.lujinxin.nextep.workspace.SidebarSide
import io.github.lujinxin.nextep.workspace.WorkspaceControlMetrics
import java.util.Date

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
    private val appScroll = object : HorizontalScrollView(context) {
        private var downX = 0f
        private var downY = 0f
        private var previousX = 0f
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                downX = event.x
                downY = event.y
                previousX = event.x
            }
            val handled = super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_MOVE && !internalAppDragActive) {
                val dx = event.x - downX
                val dy = event.y - downY
                val direction = if (event.x < previousX) 1 else -1
                if (kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy) &&
                    !canScrollHorizontally(direction)) {
                    // HorizontalScrollView owns its scroll until it reaches an edge.
                    // Voluntarily release interception for the pager's next move.
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                previousX = event.x
            }
            return handled
        }
    }.apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(appRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }
    private val leftButton = SideSelectionButton(context, SidebarSide.LEFT).apply {
        contentDescription = AppLanguage.string(context, R.string.window_side_left)
        setOnClickListener { onSidebarSideRequested(SidebarSide.LEFT) }
    }
    private val rightButton = SideSelectionButton(context, SidebarSide.RIGHT).apply {
        contentDescription = AppLanguage.string(context, R.string.window_side_right)
        setOnClickListener { onSidebarSideRequested(SidebarSide.RIGHT) }
    }
    private var sidebarSide = initialSidebarSide
    private val exitButton = ExitButton(context).apply {
        contentDescription = AppLanguage.string(context, R.string.window_exit)
        setOnClickListener { onExitRequested() }
    }
    private val settingsButton = GearButton(context).apply {
        contentDescription = AppLanguage.string(context, R.string.open_settings)
        setOnClickListener { onSettingsRequested() }
    }
    private var videoEmbedded = false
    private var videoCaptureAvailable = false
    private var videoActionListener: (() -> Unit)? = null
    private val videoActionButton = VideoActionButton(context).apply {
        contentDescription = AppLanguage.string(context, R.string.control_video_capture)
        visibility = GONE
        isFocusable = true
        setOnClickListener {
            if ((videoCaptureAvailable || videoEmbedded) && !internalAppDragActive) videoActionListener?.invoke()
        }
    }
    private var loading = false
    private var refreshPending = false
    private var lastRefreshAt = 0L
    private var refreshGeneration = 0
    private var refreshRetryCount = 0
    private val refreshRetry = Runnable { refresh(force = true) }
    private var internalAppDragActive = false
    private var deferredApps: List<TopAppRepository.AppEntry>? = null
    private val dismissHint = TextView(context).apply {
        text = AppLanguage.string(context, R.string.window_drop_background)
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
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val mediaHeight = WorkspaceControlMetrics.mediaHeightPixels(
                MeasureSpec.getSize(heightMeasureSpec), resources.displayMetrics.density,
            )
            mediaControl.layoutParams.height = mediaHeight
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private val configChangedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Read configuration through the explicit module receiver; do not trust broadcast extras.
            if (intent.action == io.github.lujinxin.nextep.config.WorkspaceConfigContract.ACTION_CHANGED ||
                intent.action == android.content.Intent.ACTION_LOCALE_CHANGED) {
                refreshRetryCount = 0
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
        leftButton.contentDescription = AppLanguage.string(context, R.string.window_side_left)
        rightButton.contentDescription = AppLanguage.string(context, R.string.window_side_right)
        exitButton.contentDescription = AppLanguage.string(context, R.string.window_exit)
        settingsButton.contentDescription = AppLanguage.string(context, R.string.open_settings)
        updateVideoActionDescription()
        dismissHint.text = AppLanguage.string(context, R.string.window_drop_background)
        mediaControl.refreshLanguage()
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
                java.text.SimpleDateFormat(
                    DateFormat.getBestDateTimePattern(AppLanguage.locale(context), skeleton), AppLanguage.locale(context),
                ).format(Date())
            }
            TopContentMode.DATE -> {
                val locale = AppLanguage.locale(context)
                java.text.SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMEd"), locale).format(Date())
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
            android.content.IntentFilter(io.github.lujinxin.nextep.config.WorkspaceConfigContract.ACTION_CHANGED).apply {
                addAction(android.content.Intent.ACTION_LOCALE_CHANGED)
            },
            Context.RECEIVER_EXPORTED)
        updateTitle()
        if (workspaceVisible && appRow.childCount == 0) refresh(force = true)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(titleTick)
        removeCallbacks(refreshRetry)
        context.unregisterReceiver(configChangedReceiver)
        super.onDetachedFromWindow()
    }

    fun setWorkspaceVisible(visible: Boolean) {
        if (visible && !workspaceVisible) refreshRetryCount = 0
        workspaceVisible = visible
        if (!visible) removeCallbacks(refreshRetry)
        else if (appRow.childCount == 0) refresh(force = true)
        updateTitle()
        mediaControl.setWorkspaceVisible(visible)
    }

    fun setMediaWallpaperLight(light: Boolean) {
        mediaControl.setWallpaperLight(light)
    }

    fun setVideoActionState(canCapture: Boolean, embedded: Boolean) {
        videoCaptureAvailable = canCapture
        videoEmbedded = embedded
        videoActionButton.isVideoEmbedded = embedded
        videoActionButton.visibility = if (canCapture || embedded) VISIBLE else GONE
        videoActionButton.isEnabled = canCapture || embedded
        updateVideoActionDescription()
    }

    fun setOnVideoActionRequested(listener: () -> Unit) {
        videoActionListener = listener
    }

    private fun updateVideoActionDescription() {
        videoActionButton.contentDescription = AppLanguage.string(context,
            if (videoEmbedded) R.string.control_video_release else R.string.control_video_capture)
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
        parent?.requestDisallowInterceptTouchEvent(false)
        deferredApps?.let { entries -> deferredApps = null; renderApps(entries) }
    }

    fun isInternalAppDragActive(): Boolean = internalAppDragActive

    fun canScrollHorizontallyAt(x: Float, y: Float, direction: Int): Boolean {
        val bounds = Rect(appScroll.scrollX, appScroll.scrollY,
            appScroll.scrollX + appScroll.width, appScroll.scrollY + appScroll.height)
        offsetDescendantRectToMyCoords(appScroll, bounds)
        return bounds.contains(x.toInt(), y.toInt()) && appScroll.canScrollHorizontally(direction)
    }

    fun refresh(force: Boolean = false) {
        removeCallbacks(refreshRetry)
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
            val config = WorkspaceConfigClient.queryOrNull(context)
            if (config != null) AppLanguage.updateHostLanguage(config.languageTag)
            val result = config?.let {
                runCatching {
                    repository.load(
                        if (config.manualAppOrder) config.appComponents else emptyList(),
                        manualOrder = config.manualAppOrder,
                    )
                }
            }
            post {
                if (generation != refreshGeneration) return@post
                loading = false
                if (config != null) {
                    onFrostStrengthChanged(config.frostStrength)
                    topConfig = config
                    updateTitle()
                    result?.onSuccess { entries ->
                        refreshRetryCount = 0
                        lastRefreshAt = System.currentTimeMillis()
                        renderApps(entries)
                    }?.onFailure { error ->
                        NeXtepLog.error("top_apps", "Unable to load App strip", error)
                        // A temporary catalogue failure must not erase the
                        // last successfully rendered user selection.
                        scheduleRefreshRetry()
                    }
                } else {
                    // A failed IPC query says nothing about the user's preferences.
                    // Retain both the title mode and the already rendered app order.
                    NeXtepLog.warn("top_apps", "Config unavailable; retaining previous top content and App order")
                    scheduleRefreshRetry()
                }
                if (refreshPending) {
                    refreshPending = false
                    refresh(force = true)
                }
            }
        }, "NeXtep-TopApps").start()
    }

    private fun scheduleRefreshRetry() {
        if (!isAttachedToWindow || !workspaceVisible) return
        removeCallbacks(refreshRetry)
        // A cold or temporarily frozen module can outlast the initial retries.
        // Keep the last successful selection and retry while the panel is visible.
        val delay = minOf(1_000L * (1L shl refreshRetryCount), REFRESH_INTERVAL_MS)
        refreshRetryCount = (refreshRetryCount + 1).coerceAtMost(4)
        postDelayed(refreshRetry, delay)
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
            videoActionButton,
            LinearLayout.LayoutParams(buttonSize, buttonSize),
        )
        addView(
            settingsButton,
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
            appRow.addView(messageView(AppLanguage.string(context, R.string.apps_none_available)))
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
            if (started) parent?.requestDisallowInterceptTouchEvent(true)
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
        val diagramSide = SidebarButtonLayout.sideInPanel(representedSide)
        val mainRect = if (diagramSide == SidebarSide.LEFT) {
            rect(8.5f, 1f, 22f, 23f)
        } else {
            rect(2f, 1f, 15.5f, 23f)
        }
        val stackLeft = if (diagramSide == SidebarSide.LEFT) {
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
    private val ringPath = Path()

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
        ringPath.rewind()
        // Rewind resets the native fill rule, so restore the hole on every draw.
        ringPath.fillType = Path.FillType.EVEN_ODD
        ringPath.addCircle(cx, cy, gearRadius * 0.68f, Path.Direction.CW)
        ringPath.addCircle(cx, cy, gearRadius * 0.28f, Path.Direction.CW)
        canvas.drawPath(ringPath, paint)
        paint.style = Paint.Style.STROKE
    }
}

private class VideoActionButton(context: Context) : ActionGlyphButton(context) {
    var isVideoEmbedded = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun drawGlyph(canvas: Canvas, paint: Paint, cx: Float, cy: Float, radius: Float) {
        val left = cx - radius
        val top = cy - radius * 0.75f
        val right = cx + radius
        val bottom = cy + radius * 0.75f
        val corner = radius * 0.12f
        canvas.drawRoundRect(left, top, right, bottom, corner, corner, paint)

        if (isVideoEmbedded) {
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(
                cx, cy, right - radius * 0.2f, bottom - radius * 0.2f,
                corner, corner, paint,
            )
            paint.style = Paint.Style.STROKE
        } else {
            val arrowX = cx + radius * 0.45f
            val arrowY = cy - radius * 0.3f
            canvas.drawLine(cx - radius * 0.45f, cy + radius * 0.3f, arrowX, arrowY, paint)
            canvas.drawLine(arrowX - radius * 0.45f, arrowY, arrowX, arrowY, paint)
            canvas.drawLine(arrowX, arrowY, arrowX, arrowY + radius * 0.45f, paint)
        }
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
