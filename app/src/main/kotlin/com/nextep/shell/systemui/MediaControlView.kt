package com.nextep.shell.systemui

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nextep.shell.logging.NeXtepLog

class MediaControlView(context: Context) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val artwork = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    private val title = mediaText(13f, Color.WHITE)
    private val artist = mediaText(10f, Color.rgb(188, 194, 204))
    private val previous = controlButton("|◀", "上一首") { transport { skipToPrevious() } }
    private val playPause = controlButton("▶", "播放") { togglePlayback() }
    private val next = controlButton("▶|", "下一首") { transport { skipToNext() } }
    private var mediaController: MediaController? = null
    private var listening = false
    private val periodicRefresh = object : Runnable {
        override fun run() {
            if (!listening) return
            refresh()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        handler.post { bindBestController(sessions) }
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = render()
        override fun onPlaybackStateChanged(state: PlaybackState?) = render()
        override fun onSessionDestroyed() = refresh()
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(5), dp(8), dp(5))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(Color.rgb(18, 22, 29))
            setStroke(dp(1), Color.rgb(58, 67, 81))
        }
        addView(artwork, LayoutParams(dp(46), dp(46)))
        addView(
            LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(4), 0)
                addView(title, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
                addView(artist, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            },
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
        )
        addView(previous, LayoutParams(dp(36), LayoutParams.MATCH_PARENT))
        addView(playPause, LayoutParams(dp(38), LayoutParams.MATCH_PARENT))
        addView(next, LayoutParams(dp(36), LayoutParams.MATCH_PARENT))
        setOnClickListener { openSession() }
        renderNoMedia()
    }

    fun setWorkspaceVisible(visible: Boolean) {
        if (visible) startListening() else stopListening()
    }

    fun refresh() {
        if (!listening) return
        runCatching { bindBestController(sessionManager?.getActiveSessions(null)) }
            .onFailure {
                NeXtepLog.warn("media_control", "Unable to query active sessions", it)
                bindBestController(null)
            }
    }

    override fun onDetachedFromWindow() {
        stopListening()
        super.onDetachedFromWindow()
    }

    private fun startListening() {
        if (listening) {
            refresh()
            return
        }
        val manager = sessionManager ?: run {
            renderNoMedia()
            return
        }
        runCatching {
            manager.addOnActiveSessionsChangedListener(sessionsListener, null, handler)
            listening = true
            refresh()
            handler.removeCallbacks(periodicRefresh)
            handler.postDelayed(periodicRefresh, REFRESH_INTERVAL_MS)
        }.onFailure {
            NeXtepLog.warn("media_control", "Active-session listener unavailable", it)
            renderNoMedia()
        }
    }

    private fun stopListening() {
        if (listening) {
            runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        }
        listening = false
        handler.removeCallbacks(periodicRefresh)
        bindController(null)
    }

    private fun bindBestController(controllers: List<MediaController>?) {
        bindController(
            controllers.orEmpty()
                .filter(::isUsableController)
                .maxByOrNull(::controllerScore),
        )
    }

    private fun bindController(controller: MediaController?) {
        if (sameSession(mediaController, controller)) {
            render()
            return
        }
        mediaController?.let { current ->
            runCatching { current.unregisterCallback(controllerCallback) }
        }
        mediaController = controller
        controller?.let { current ->
            runCatching { current.registerCallback(controllerCallback, handler) }
                .onFailure { NeXtepLog.warn("media_control", "Callback registration failed", it) }
        }
        render()
    }

    private fun render() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(::render)
            return
        }
        val controller = mediaController ?: run {
            renderNoMedia()
            return
        }
        val metadata = runCatching { controller.metadata }.getOrNull()
        val state = runCatching { controller.playbackState }.getOrNull()
        val appLabel = applicationLabel(controller.packageName)
        title.text = firstText(
            metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            appLabel,
            "未播放",
        )
        artist.text = firstText(
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            appLabel,
            "NeXtep 媒体控制",
        )
        val art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        artwork.setImageDrawable(
            art?.let { BitmapDrawable(resources, it) }
                ?: runCatching { context.packageManager.getApplicationIcon(controller.packageName) }
                    .getOrNull(),
        )
        val actions = state?.actions ?: 0L
        val playing = state?.state == PlaybackState.STATE_PLAYING ||
            state?.state == PlaybackState.STATE_BUFFERING
        setControlEnabled(previous, actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
        setControlEnabled(next, actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
        setControlEnabled(
            playPause,
            actions and (
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE
                ) != 0L || state != null,
        )
        playPause.text = if (playing) "Ⅱ" else "▶"
        playPause.contentDescription = if (playing) "暂停" else "播放"
        alpha = 1f
    }

    private fun renderNoMedia() {
        title.text = "未播放"
        artist.text = "打开音乐应用后显示控制"
        artwork.setImageDrawable(null)
        setControlEnabled(previous, false)
        setControlEnabled(playPause, false)
        setControlEnabled(next, false)
        playPause.text = "▶"
        alpha = 0.82f
    }

    private fun togglePlayback() {
        val controller = mediaController ?: return
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING ||
            controller.playbackState?.state == PlaybackState.STATE_BUFFERING
        runCatching {
            if (playing) controller.transportControls.pause() else controller.transportControls.play()
        }.onFailure { NeXtepLog.warn("media_control", "Play/pause failed", it) }
    }

    private fun transport(block: MediaController.TransportControls.() -> Unit) {
        val controls = mediaController?.transportControls ?: return
        runCatching { controls.block() }
            .onFailure { NeXtepLog.warn("media_control", "Transport action failed", it) }
    }

    private fun openSession() {
        val pendingIntent: PendingIntent = mediaController?.sessionActivity ?: return
        runCatching { pendingIntent.send() }
            .onFailure { NeXtepLog.warn("media_control", "Session Activity failed", it) }
    }

    private fun controllerScore(controller: MediaController): Int {
        val state = runCatching { controller.playbackState?.state }.getOrNull()
        val metadata = runCatching { controller.metadata }.getOrNull()
        val playbackScore = when (state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING -> 8
            PlaybackState.STATE_PAUSED -> 3
            PlaybackState.STATE_NONE, null -> 0
            else -> 1
        }
        val metadataScore = if (
            !metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() ||
            !metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).isNullOrBlank()
        ) 4 else 0
        return playbackScore + metadataScore
    }

    private fun isUsableController(controller: MediaController): Boolean {
        val state = runCatching { controller.playbackState }.getOrNull() ?: return false
        if (state.state == PlaybackState.STATE_PLAYING ||
            state.state == PlaybackState.STATE_BUFFERING ||
            state.state == PlaybackState.STATE_CONNECTING ||
            state.state == PlaybackState.STATE_PAUSED ||
            state.state == PlaybackState.STATE_FAST_FORWARDING ||
            state.state == PlaybackState.STATE_REWINDING ||
            state.state == PlaybackState.STATE_SKIPPING_TO_NEXT ||
            state.state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS ||
            state.state == PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM
        ) {
            return true
        }
        val transportActions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
        if (state.state == PlaybackState.STATE_ERROR ||
            state.actions and transportActions == 0L
        ) {
            return false
        }
        return state.state != PlaybackState.STATE_STOPPED ||
            isPackageVisible(controller.packageName)
    }

    private fun isPackageVisible(packageName: String): Boolean = runCatching {
        context.getSystemService(ActivityManager::class.java)
            ?.runningAppProcesses
            .orEmpty()
            .any { process ->
                process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE &&
                    process.pkgList?.contains(packageName) == true
            }
    }.getOrDefault(false)

    private fun sameSession(left: MediaController?, right: MediaController?): Boolean =
        left === right || left != null && right != null && runCatching {
            left.packageName == right.packageName && left.sessionToken == right.sessionToken
        }.getOrDefault(false)

    private fun applicationLabel(packageName: String): String? = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrNull()

    private fun mediaText(size: Float, color: Int) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
    }

    private fun controlButton(
        label: String,
        description: String,
        action: () -> Unit,
    ) = TextView(context).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        contentDescription = description
        isClickable = true
        setOnClickListener { action() }
    }

    private fun setControlEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.34f
    }

    private fun firstText(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }.orEmpty()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REFRESH_INTERVAL_MS = 1_500L
    }
}
