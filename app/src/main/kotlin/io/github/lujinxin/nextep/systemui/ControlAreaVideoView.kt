package io.github.lujinxin.nextep.systemui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.ControlAreaVideoContract
import io.github.lujinxin.nextep.workspace.ControlAreaVideoState
import io.github.lujinxin.nextep.workspace.WorkspaceControlMetrics
import java.util.concurrent.Executors

/** A persistent rendering host. Switching pages never destroys or hides this SurfaceView. */
internal class ControlAreaVideoView(
    context: Context,
    private val onStateChanged: (ControlAreaVideoState) -> Unit,
    private val onReleaseRequested: () -> Unit,
    private val canAutomaticallyCapture: () -> Boolean = { true },
) : FrameLayout(context), SurfaceHolder.Callback {
    private var session: IBinder = Binder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var state = ControlAreaVideoState()
    private var workspaceVisible = false
    private var locked = false
    private var captureRequested = false
    private var surfaceReady = false
    private var receiverRegistered = false
    private var releaseToken: IBinder? = null
    private var releaseConfirmed = false
    private var remoteMayOwnSource = false
    private var surfaceParent: SurfaceControl? = null
    private var hostSurface: SurfaceControl? = null
    private var hostGeometrySupported = true
    private var panelRotation = 0f
    private var pageActive = false
    private var controlsVisible = false
    private val capturePolicy = ControlAreaVideoCapturePolicy()
    private var automaticCaptureScheduled = false
    private var automaticCaptureAt = 0L
    private var automaticCaptureSourceId = 0L
    private val automaticCapture = object : Runnable {
        override fun run() {
            automaticCaptureScheduled = false
            if (!canAutomaticallyCapture()) {
                scheduleAutomaticCapture()
                return
            }
            requestCapture(automatic = true)
        }
    }
    private val hideControls = Runnable { setControlsVisible(false) }
    private val controlTapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapUp(event: MotionEvent) = performClick()
    })
    private val pageCrop = Rect()
    private val surfaceMatrixMethod by lazy {
        SurfaceControl.Transaction::class.java.getMethod("setMatrix", SurfaceControl::class.java,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
    }
    private val surfaceView = SurfaceView(context).apply {
        // The host stays behind its own window so the entry/release controls remain visible.
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this@ControlAreaVideoView)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val titleView = TextView(context).apply {
        textSize = 16f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
    }
    private val hintView = TextView(context).apply {
        textSize = 12f
        setTextColor(Color.rgb(212, 222, 232))
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(3), dp(16), dp(6))
    }
    private val captureButton = actionButton().apply {
        setOnClickListener { captureVideo() }
    }
    private val playbackButton = VideoGlassButton(context, VideoGlassButton.Icon.PAUSE).apply {
        setOnClickListener { changePlayback() }
    }
    private val releaseButton = VideoGlassButton(context, VideoGlassButton.Icon.RETURN).apply {
        setOnClickListener { releaseVideo() }
    }
    private val closeButton = VideoGlassButton(context, VideoGlassButton.Icon.CLOSE).apply {
        setOnClickListener { closeVideo() }
    }
    private val videoControls = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(playbackButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(releaseButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(closeButton, LinearLayout.LayoutParams(dp(48), dp(48)))
    }
    private val emptyContent = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.rgb(15, 22, 31))
        addView(titleView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(hintView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(captureButton, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)))
    }
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val next = runCatching { ControlAreaVideoContract.readState(context, intent) }.getOrNull() ?: return
            if (next.session != session) return
            if (next.releaseToken != null) releaseToken = next.releaseToken
            // A retained session can still receive a capture reply after hide/lock/release.
            // Only a capture requested during the current visible lifecycle may enter video.
            state = if (!workspaceVisible || locked || !captureRequested) next.copy(embedded = false) else next
            renderState()
            onStateChanged(state)
            scheduleAutomaticCapture()
        }
    }

    init {
        clipChildren = true
        clipToPadding = true
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Only the empty-state content is opaque. An embedded source needs the SurfaceView hole.
        addView(emptyContent, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(videoControls, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48), Gravity.TOP or Gravity.END).apply {
            // Compact glass icons retain their 48dp touch targets below the status band.
            topMargin = dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP - 4)
            marginEnd = dp(8)
        })
        renderState()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        runCatching {
            ControlAreaVideoContract.registerStateReceiver(context, stateReceiver, mainHandler)
            receiverRegistered = true
        }.onFailure { NeXtepLog.warn("control_video", "Unable to observe video host state", it) }
        renderState()
        post { publishHost() }
    }

    override fun onDetachedFromWindow() {
        workspaceVisible = false
        captureRequested = false
        cancelAutomaticCapture()
        capturePolicy.reset(clearManualReturn = true)
        setControlsVisible(false)
        publishHost(active = false)
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(stateReceiver) }
            receiverRegistered = false
        }
        destroyHostSurface()
        super.onDetachedFromWindow()
    }

    fun setWorkspaceState(visible: Boolean, isLocked: Boolean) {
        workspaceVisible = visible
        locked = isLocked
        if (!visible || isLocked) {
            cancelAutomaticCapture()
            capturePolicy.reset(clearManualReturn = !visible)
        }
        if (!visible || isLocked) {
            captureRequested = false
            state = state.copy(embedded = false)
        }
        renderState()
        onStateChanged(state)
        publishHost()
    }

    fun canCaptureVideo(): Boolean = state.available && !state.embedded &&
        ControlAreaVideoCapturePolicy.allowsCapture(state.reason) &&
        releaseToken != null && workspaceVisible && !locked && surfaceReady &&
        hostSurface?.isValid == true && hostGeometrySupported

    fun captureVideo() = requestCapture(automatic = false)

    private fun requestCapture(automatic: Boolean) {
        if (!canCaptureVideo()) return
        val sourceId = state.sourceId
        val now = SystemClock.uptimeMillis()
        capturePolicy.update(sourceId, state.available, state.embedded, state.reason, now)
        if (!capturePolicy.recordAttempt(sourceId, automatic, now)) {
            if (automatic) scheduleAutomaticCapture()
            return
        }
        cancelAutomaticCapture()
        captureRequested = true
        releaseConfirmed = false
        // Set before IPC: even a failed send may have reached the remote receiver.
        remoteMayOwnSource = true
        NeXtepLog.info("control_video", "Capture requested source=$sourceId automatic=$automatic " +
            "attempt=${capturePolicy.attemptCount}")
        runCatching {
            ControlAreaVideoContract.sendCommand(context, session, ControlAreaVideoContract.COMMAND_CAPTURE, sourceId)
        }.onFailure {
            captureRequested = false
            NeXtepLog.warn("control_video", "Video capture request failed", it)
        }
        // A failed send, a lost reply or a temporarily unready native window must not
        // consume the only attempt. A successful reply cancels this bounded watchdog.
        scheduleAutomaticCapture()
    }

    fun releaseVideo() {
        if (!state.embedded || !captureRequested || !workspaceVisible || locked) return
        capturePolicy.recordManualReturn(state.sourceId)
        cancelAutomaticCapture()
        NeXtepLog.info("control_video", "Native window requested source=${state.sourceId}")
        captureRequested = false
        setControlsVisible(false)
        runCatching {
            ControlAreaVideoContract.sendCommand(context, session, ControlAreaVideoContract.COMMAND_RELEASE)
        }.onFailure { NeXtepLog.warn("control_video", "Video release request failed", it) }
        onReleaseRequested()
    }

    private fun changePlayback() {
        if (!canShowControls() || !state.canControlPlayback) return
        val command = if (state.playing) ControlAreaVideoContract.COMMAND_PAUSE else ControlAreaVideoContract.COMMAND_PLAY
        runCatching {
            ControlAreaVideoContract.sendCommand(context, session, command, state.sourceId)
        }.onFailure { NeXtepLog.warn("control_video", "Video playback request failed", it) }
    }

    private fun closeVideo() {
        if (!canShowControls()) return
        runCatching {
            ControlAreaVideoContract.sendCommand(context, session, ControlAreaVideoContract.COMMAND_CLOSE, state.sourceId)
            capturePolicy.recordManualReturn(state.sourceId)
            cancelAutomaticCapture()
            setControlsVisible(false)
            // Keep the host until system_server confirms that the native source closed.
        }.onFailure { NeXtepLog.warn("control_video", "Video close request failed", it) }
    }

    private fun scheduleAutomaticCapture() {
        val now = SystemClock.uptimeMillis()
        capturePolicy.update(state.sourceId, state.available, state.embedded, state.reason, now)
        val delay = if (canCaptureVideo()) capturePolicy.nextAutomaticDelay(now) else null
        if (delay == null) {
            cancelAutomaticCapture()
            return
        }
        val nextAt = now + maxOf(delay, if (canAutomaticallyCapture()) 0L else 150L)
        // Repeated state/host publications must not keep postponing the same attempt.
        if (automaticCaptureScheduled && automaticCaptureSourceId == state.sourceId &&
            automaticCaptureAt <= nextAt) return
        cancelAutomaticCapture()
        automaticCaptureScheduled = true
        automaticCaptureAt = nextAt
        automaticCaptureSourceId = state.sourceId
        mainHandler.postAtTime(automaticCapture, nextAt)
    }

    private fun cancelAutomaticCapture() {
        mainHandler.removeCallbacks(automaticCapture)
        automaticCaptureScheduled = false
        automaticCaptureSourceId = 0L
    }

    fun setPageActive(active: Boolean) {
        if (pageActive == active) return
        pageActive = active
        if (!active) setControlsVisible(false) else renderState()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) mainHandler.removeCallbacks(hideControls)
        val handled = super.dispatchTouchEvent(event)
        if (controlsVisible && (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL)) scheduleControlsHide()
        return handled
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!canShowControls()) return super.onTouchEvent(event)
        // Only a tap reveals controls; paging, vertical drags and cancellation do not.
        controlTapDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        if (!canShowControls()) return super.performClick()
        super.performClick()
        setControlsVisible(!controlsVisible)
        return true
    }

    private fun canShowControls() = state.embedded && captureRequested && workspaceVisible && !locked && pageActive

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible && canShowControls()
        mainHandler.removeCallbacks(hideControls)
        renderState()
        if (controlsVisible) scheduleControlsHide()
    }

    private fun scheduleControlsHide() {
        mainHandler.removeCallbacks(hideControls)
        val accessibility = context.getSystemService(AccessibilityManager::class.java)
        val timeout = accessibility?.getRecommendedTimeoutMillis(3_000, AccessibilityManager.FLAG_CONTENT_CONTROLS) ?: 3_000
        mainHandler.postDelayed(hideControls, timeout.toLong())
    }

    /** Reconfigure after layout so the remote leash fits the current local viewport. */
    fun refreshHost() {
        post { publishHost() }
    }

    fun setPanelRotation(rotationDegrees: Float) {
        panelRotation = rotationDegrees
        if (hostSurface != null && !updateHostGeometry()) publishHost(active = false)
        refreshHost()
    }

    fun setPageViewport(pageTranslationX: Float, viewportWidth: Int, viewportHeight: Int) {
        // This is deliberately local to the rotated panel subtree, not screen coordinates.
        val left = (-pageTranslationX).coerceIn(0f, viewportWidth.toFloat()).toInt()
        val right = (viewportWidth - pageTranslationX).coerceIn(0f, viewportWidth.toFloat()).toInt()
        pageCrop.set(left, 0, right.coerceAtLeast(left), viewportHeight.coerceAtLeast(0))
        clipBounds = pageCrop
        // Crop the owned container, not the framework SurfaceView control. This also
        // works on Android versions that do not clip SurfaceViews with parent Views.
        if (hostSurface != null && !updateHostGeometry()) publishHost(active = false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refreshHost()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        publishHost()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = true
        publishHost()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        captureRequested = false
        cancelAutomaticCapture()
        capturePolicy.reset(clearManualReturn = false)
        state = state.copy(embedded = false)
        setControlsVisible(false)
        onStateChanged(state)
        publishHost(active = false)
        destroyHostSurface()
    }

    private fun publishHost(active: Boolean = workspaceVisible && !locked) {
        val host = if (active && isAttachedToWindow && surfaceReady) {
            runCatching {
                val parent = SurfaceView::class.java.getMethod("getSurfaceControl")
                    .invoke(surfaceView) as? SurfaceControl
                if (parent?.isValid == true) ensureHostSurface(parent) else null
            }.onFailure { NeXtepLog.warn("control_video", "Surface host unavailable", it) }
                .getOrNull()?.takeIf { it.isValid }
        } else null
        val hostActive = active && host != null && surfaceView.width > 0 && surfaceView.height > 0
        val publicationSession = session
        if (!hostActive && !releaseRemoteBeforeTeardown()) quarantineHostSurface()
        if (hostActive) releaseConfirmed = false
        runCatching {
            ControlAreaVideoContract.publishHost(
                context, publicationSession, host.takeIf { hostActive }, surfaceView.width, surfaceView.height, hostActive,
            )
        }.onFailure { NeXtepLog.warn("control_video", "Video host publication failed", it) }
        if (hostActive) scheduleAutomaticCapture() else cancelAutomaticCapture()
    }

    private fun releaseRemoteBeforeTeardown(): Boolean {
        if (releaseConfirmed) return true
        if (hostSurface == null && !remoteMayOwnSource) return true
        val token = releaseToken ?: return !remoteMayOwnSource
        // ACK means the restoration transaction committed, not merely that IPC completed.
        val acknowledged = runCatching { ControlAreaVideoContract.releaseHostNow(token, session) }
            .onFailure { NeXtepLog.warn("control_video", "Immediate video restoration unavailable", it) }
            .getOrDefault(false)
        if (acknowledged) {
            releaseConfirmed = true
            remoteMayOwnSource = false
        }
        return acknowledged
    }

    private fun ensureHostSurface(parent: SurfaceControl): SurfaceControl? {
        if (surfaceParent !== parent || hostSurface?.isValid != true) {
            destroyHostSurface()
            val builder = SurfaceControl.Builder().setName("NeXtep-ControlAreaVideoHost").setParent(parent)
            builder.javaClass.getMethod("setContainerLayer").invoke(builder)
            hostSurface = builder.build()
            surfaceParent = parent
            hostGeometrySupported = true
        }
        return hostSurface?.takeIf { updateHostGeometry() }
    }

    private fun updateHostGeometry(): Boolean {
        val host = hostSurface?.takeIf { it.isValid } ?: return false
        if (!hostGeometrySupported) return false
        val width = surfaceView.width.toFloat()
        val height = surfaceView.height.toFloat()
        if (width <= 0f || height <= 0f) return false
        return runCatching {
            SurfaceControl.Transaction().use { transaction ->
                if (panelRotation == 0f) {
                    surfaceMatrixMethod.invoke(transaction, host, 1f, 0f, 0f, 1f)
                    transaction.setPosition(host, 0f, 0f)
                } else {
                    // SurfaceView positions its control using the transformed bounding box,
                    // with axis scales H/W and W/H, rather than inheriting View rotation.
                    // Compensate only our container by S^-1 * R; the source stays untouched.
                    val clockwise = panelRotation > 0f
                    val sign = if (clockwise) 1f else -1f
                    surfaceMatrixMethod.invoke(transaction, host, 0f, sign * height / width,
                        -sign * width / height, 0f)
                    transaction.setPosition(host, if (clockwise) width else 0f,
                        if (clockwise) 0f else height)
                }
                transaction.setCrop(host, pageCrop)
                transaction.setLayer(host, 1)
                transaction.setVisibility(host, !pageCrop.isEmpty)
                // Submit the owned crop/matrix with the View hierarchy frame that
                // carries SurfaceView's translated position and transparent hole.
                if (rootSurfaceControl?.applyTransactionOnDraw(transaction) != true) transaction.apply()
                invalidate()
            }
        }.onFailure {
            hostGeometrySupported = false
            NeXtepLog.warn("control_video", "Video viewport transform unavailable", it)
        }.isSuccess
    }

    private fun destroyHostSurface() {
        val host = hostSurface
        if (host != null && !releaseRemoteBeforeTeardown()) {
            quarantineHostSurface()
            return
        }
        hostSurface = null
        surfaceParent = null
        if (host != null) {
            runCatching {
                if (host.isValid) SurfaceControl.Transaction().use { transaction ->
                    transaction.reparent(host, null).apply()
                }
            }.onFailure { NeXtepLog.warn("control_video", "Video host cleanup failed", it) }
            runCatching { host.release() }
        }
    }

    private fun quarantineHostSurface() {
        val host = hostSurface ?: return
        val oldSession = session
        val oldToken = releaseToken
        // Only detach our wrapper. The framework parent is never modified or released.
        runCatching {
            if (host.isValid) SurfaceControl.Transaction().use { transaction ->
                transaction.reparent(host, null).apply()
            }
        }.onFailure { NeXtepLog.warn("control_video", "Unable to isolate pending video host", it) }
        ControlAreaVideoHostQuarantine.retain(host, oldToken, oldSession)
        hostSurface = null
        surfaceParent = null
        // Old retries must not release a new capture on this retained View instance.
        session = Binder()
        releaseToken = null
        releaseConfirmed = false
        remoteMayOwnSource = false
        captureRequested = false
        cancelAutomaticCapture()
        capturePolicy.reset(clearManualReturn = false)
        state = ControlAreaVideoState()
        renderState()
        mainHandler.post { onStateChanged(state) }
    }

    private fun renderState() {
        titleView.text = AppLanguage.string(context, R.string.control_video_title)
        captureButton.text = AppLanguage.string(context, R.string.control_video_capture)
        releaseButton.contentDescription = AppLanguage.string(context, R.string.control_video_release)
        closeButton.contentDescription = AppLanguage.string(context, R.string.control_video_close)
        playbackButton.setIcon(if (state.playing) VideoGlassButton.Icon.PAUSE else VideoGlassButton.Icon.PLAY)
        playbackButton.contentDescription = AppLanguage.string(context,
            if (state.playing) R.string.media_pause else R.string.media_play)
        playbackButton.isEnabled = state.canControlPlayback
        playbackButton.alpha = if (state.canControlPlayback) 1f else 0.4f
        hintView.text = AppLanguage.string(context, when {
            state.reason == "secure" -> R.string.control_video_secure
            state.reason == "unsupported" -> R.string.control_video_unsupported
            state.reason == "no_source" -> R.string.control_video_unavailable
            state.available -> R.string.control_video_hint
            else -> R.string.control_video_waiting
        })
        emptyContent.visibility = if (state.embedded) GONE else VISIBLE
        val canShow = canShowControls()
        if (!canShow) {
            controlsVisible = false
            mainHandler.removeCallbacks(hideControls)
        }
        videoControls.visibility = if (controlsVisible) VISIBLE else GONE
        isClickable = canShow
        isFocusable = canShow
        contentDescription = if (canShow) AppLanguage.string(context,
            if (controlsVisible) R.string.control_video_hide_controls else R.string.control_video_show_controls) else null
        captureButton.visibility = if (state.available && ControlAreaVideoCapturePolicy.allowsCapture(state.reason)) VISIBLE else GONE
        captureButton.isEnabled = canCaptureVideo()
    }

    private fun actionButton() = TextView(context).apply {
        textSize = 12f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        background = GradientDrawable().apply {
            setColor(Color.argb(215, 35, 54, 71))
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.argb(90, 219, 233, 244))
        }
        isClickable = true
        isFocusable = true
        minHeight = dp(36)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/** Preserve a borrowed-source ancestor until system_server acknowledges restoration. */
private object ControlAreaVideoHostQuarantine {
    private data class PendingHost(val surface: SurfaceControl, val token: IBinder?, val session: IBinder)
    private val retained = mutableListOf<PendingHost>()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NeXtep-VideoHostRestore").apply { isDaemon = true }
    }

    fun retain(surface: SurfaceControl, token: IBinder?, session: IBinder) {
        val pending = PendingHost(surface, token, session)
        synchronized(retained) { retained += pending }
        NeXtepLog.warn("control_video", "Video host retained while restoration awaits commit")
        worker.execute {
            repeat(3) { attempt ->
                if (attempt > 0) Thread.sleep(200L * attempt)
                val restored = runCatching {
                    ControlAreaVideoContract.releaseHostNow(pending.token, pending.session)
                }.onFailure { NeXtepLog.warn("control_video", "Deferred video restoration failed", it) }
                    .getOrDefault(false)
                if (restored) {
                    runCatching { pending.surface.release() }
                    synchronized(retained) { retained.remove(pending) }
                    return@execute
                }
            }
            // Keep the strong reference for the process lifetime. Releasing an ancestor
            // without a committed restoration could otherwise destroy the borrowed source.
            NeXtepLog.warn("control_video", "Video restoration remains unconfirmed; retaining isolated host")
        }
    }
}
