package io.github.lujinxin.nextep.workspace

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaSession
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.view.SurfaceControl

/** Ephemeral host capabilities. Surface and Binder handles must never be persisted in settings. */
internal object ControlAreaVideoContract {
    const val ACTION_HOST = "io.github.lujinxin.nextep.action.CONTROL_VIDEO_HOST"
    const val ACTION_COMMAND = "io.github.lujinxin.nextep.action.CONTROL_VIDEO_COMMAND"
    const val ACTION_STATE = "io.github.lujinxin.nextep.action.CONTROL_VIDEO_STATE"
    const val COMMAND_CAPTURE = "capture"
    const val COMMAND_RELEASE = "release"
    const val COMMAND_PLAY = "play"
    const val COMMAND_PAUSE = "pause"
    const val COMMAND_TOGGLE_PLAYBACK = "toggle_playback"
    const val COMMAND_CLOSE = "close"
    const val COMMAND_SEEK = "seek"
    const val COMMAND_FULLSCREEN = "fullscreen"
    const val REASON_NOT_READY = "not_ready"
    const val HOST_PERMISSION = "android.permission.STATUS_BAR_SERVICE"
    const val EXTRA_SESSION = "session"
    const val EXTRA_SURFACE = "surface"
    const val EXTRA_WIDTH = "width"
    const val EXTRA_HEIGHT = "height"
    const val EXTRA_ACTIVE = "active"
    const val EXTRA_FILL_VIEWPORT = "fill_viewport"
    const val EXTRA_NATIVE_INPUT = "native_input"
    const val EXTRA_CONTROLS_VISIBLE = "controls_visible"
    const val EXTRA_MEDIA_SESSION = "media_session"
    const val EXTRA_POSITION_MS = "position_ms"
    const val EXTRA_DURATION_MS = "duration_ms"
    const val EXTRA_MEDIA_ID = "media_id"
    const val EXTRA_COMMAND = "command"
    const val EXTRA_SOURCE_ID = "source_id"
    const val RELEASE_DESCRIPTOR = "io.github.lujinxin.nextep.ControlAreaVideoRelease"
    private const val EXTRA_IDENTITY = "identity"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    fun publishHost(
        context: Context,
        session: IBinder,
        surface: SurfaceControl?,
        width: Int,
        height: Int,
        active: Boolean,
        fillViewport: Boolean = false,
        nativeInput: Boolean = false,
        controlsVisible: Boolean = false,
    ) {
        context.sendBroadcast(request(context, session, ACTION_HOST)
            .putExtra(EXTRA_SURFACE, surface)
            .putExtra(EXTRA_WIDTH, width)
            .putExtra(EXTRA_HEIGHT, height)
            .putExtra(EXTRA_ACTIVE, active)
            .putExtra(EXTRA_FILL_VIEWPORT, fillViewport)
            .putExtra(EXTRA_NATIVE_INPUT, nativeInput)
            .putExtra(EXTRA_CONTROLS_VISIBLE, controlsVisible), HOST_PERMISSION)
    }

    fun sendCommand(
        context: Context,
        session: IBinder,
        command: String,
        sourceId: Long = 0L,
        positionMs: Long = 0L,
        mediaSessionToken: MediaSession.Token? = null,
        durationMs: Long = 0L,
        mediaId: String? = null,
    ) {
        require(command in setOf(COMMAND_CAPTURE, COMMAND_RELEASE, COMMAND_PLAY, COMMAND_PAUSE,
            COMMAND_TOGGLE_PLAYBACK, COMMAND_CLOSE, COMMAND_SEEK, COMMAND_FULLSCREEN))
        require(command != COMMAND_SEEK || (positionMs >= 0L && durationMs > 0L && mediaSessionToken != null))
        context.sendBroadcast(request(context, session, ACTION_COMMAND)
            .putExtra(EXTRA_COMMAND, command)
            .putExtra(EXTRA_SOURCE_ID, sourceId)
            .putExtra(EXTRA_POSITION_MS, positionMs)
            .putExtra(EXTRA_DURATION_MS, durationMs)
            .putExtra(EXTRA_MEDIA_ID, mediaId)
            .putExtra(EXTRA_MEDIA_SESSION, mediaSessionToken), HOST_PERMISSION)
    }

    fun registerStateReceiver(context: Context, receiver: BroadcastReceiver, handler: Handler) {
        context.registerReceiver(receiver, IntentFilter(ACTION_STATE), HOST_PERMISSION,
            handler, Context.RECEIVER_EXPORTED)
    }

    fun readState(context: Context, intent: Intent): ControlAreaVideoState? {
        if (intent.action != ACTION_STATE || !trustedIdentity(context, intent, "android")) return null
        return ControlAreaVideoState(
            available = intent.getBooleanExtra("available", false),
            embedded = intent.getBooleanExtra("embedded", false),
            packageName = intent.getStringExtra("package"),
            sourceId = intent.getLongExtra(EXTRA_SOURCE_ID, 0L),
            reason = intent.getStringExtra("reason"),
            session = intent.extras?.getBinder(EXTRA_SESSION),
            releaseToken = intent.extras?.getBinder("release_token"),
            playing = intent.getBooleanExtra("playing", true),
            canControlPlayback = intent.getBooleanExtra("can_control_playback", false),
            playbackToggleOnly = intent.getBooleanExtra("playback_toggle_only", false),
            nativeInput = intent.getBooleanExtra(EXTRA_NATIVE_INPUT, false),
            sourceWidth = intent.getIntExtra("source_width", 0),
            sourceHeight = intent.getIntExtra("source_height", 0),
            nativeSeekAvailable = intent.getBooleanExtra("native_seek_available", false),
            canSeek = intent.getBooleanExtra("can_seek", false),
            durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L),
            positionMs = intent.getLongExtra(EXTRA_POSITION_MS, -1L),
            positionUpdatedAt = intent.getLongExtra("position_updated_at", 0L),
            playbackSpeed = intent.getFloatExtra("playback_speed", 0f),
            mediaSessionToken = intent.getParcelableExtra(EXTRA_MEDIA_SESSION, MediaSession.Token::class.java),
            mediaId = intent.getStringExtra(EXTRA_MEDIA_ID),
            nativeCloseBounds = ControlAreaVideoButtonBounds.decode(intent.getIntArrayExtra("native_close_bounds")),
            nativeFullscreenBounds = ControlAreaVideoButtonBounds.decode(intent.getIntArrayExtra("native_fullscreen_bounds")),
        )
    }

    fun trustedHost(context: Context, intent: Intent): Boolean =
        (intent.action == ACTION_HOST || intent.action == ACTION_COMMAND) &&
            trustedIdentity(context, intent, SYSTEM_UI_PACKAGE)

    fun publishState(context: Context, state: ControlAreaVideoState) {
        val response = Intent(ACTION_STATE).setPackage(SYSTEM_UI_PACKAGE)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra(EXTRA_IDENTITY, identity(context))
            .putExtra("available", state.available)
            .putExtra("embedded", state.embedded)
            .putExtra("package", state.packageName)
            .putExtra(EXTRA_SOURCE_ID, state.sourceId)
            .putExtra("reason", state.reason)
            .putExtra("playing", state.playing)
            .putExtra("can_control_playback", state.canControlPlayback)
            .putExtra("playback_toggle_only", state.playbackToggleOnly)
            .putExtra(EXTRA_NATIVE_INPUT, state.nativeInput)
            .putExtra("source_width", state.sourceWidth)
            .putExtra("source_height", state.sourceHeight)
            .putExtra("native_seek_available", state.nativeSeekAvailable)
            .putExtra("can_seek", state.canSeek)
            .putExtra(EXTRA_DURATION_MS, state.durationMs)
            .putExtra(EXTRA_POSITION_MS, state.positionMs)
            .putExtra("position_updated_at", state.positionUpdatedAt)
            .putExtra("playback_speed", state.playbackSpeed)
            .putExtra(EXTRA_MEDIA_SESSION, state.mediaSessionToken)
            .putExtra(EXTRA_MEDIA_ID, state.mediaId)
            .putExtra("native_close_bounds", state.nativeCloseBounds?.encode())
            .putExtra("native_fullscreen_bounds", state.nativeFullscreenBounds?.encode())
            .putExtras(Bundle().apply {
                putBinder(EXTRA_SESSION, state.session)
                putBinder("release_token", state.releaseToken)
            })
        context.sendBroadcast(response, HOST_PERMISSION)
    }

    /** Restore the source before SurfaceView tears down its parent hierarchy. */
    fun releaseHostNow(token: IBinder?, session: IBinder): Boolean {
        if (token == null) return false
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        return try {
            request.writeInterfaceToken(RELEASE_DESCRIPTOR)
            request.writeStrongBinder(session)
            if (!token.transact(IBinder.FIRST_CALL_TRANSACTION, request, response, 0)) return false
            response.readException()
            response.readInt() == 1
        } finally {
            request.recycle()
            response.recycle()
        }
    }

    private fun request(context: Context, session: IBinder, action: String): Intent =
        Intent(action).setPackage("android").addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra(EXTRA_IDENTITY, identity(context))
            .putExtras(Bundle().apply { putBinder(EXTRA_SESSION, session) })

    private fun identity(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0x4E56,
        Intent("io.github.lujinxin.nextep.action.CONTROL_VIDEO_IDENTITY").setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun trustedIdentity(context: Context, intent: Intent, expected: String): Boolean = runCatching {
        val identity = intent.getParcelableExtra(EXTRA_IDENTITY, PendingIntent::class.java)
            ?: return@runCatching false
        val expectedUid = context.packageManager.getApplicationInfo(expected, 0).uid
        identity.creatorPackage == expected && identity.creatorUid == expectedUid &&
            (expected != "android" || expectedUid == Process.SYSTEM_UID)
    }.getOrDefault(false)
}

internal data class ControlAreaVideoState(
    val available: Boolean = false,
    val embedded: Boolean = false,
    val packageName: String? = null,
    val sourceId: Long = 0L,
    val reason: String? = null,
    val session: IBinder? = null,
    val releaseToken: IBinder? = null,
    val playing: Boolean = true,
    val canControlPlayback: Boolean = false,
    val playbackToggleOnly: Boolean = false,
    val nativeInput: Boolean = false,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val nativeSeekAvailable: Boolean = false,
    val canSeek: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = -1L,
    val positionUpdatedAt: Long = 0L,
    val playbackSpeed: Float = 0f,
    val mediaSessionToken: MediaSession.Token? = null,
    val mediaId: String? = null,
    val nativeCloseBounds: ControlAreaVideoButtonBounds? = null,
    val nativeFullscreenBounds: ControlAreaVideoButtonBounds? = null,
)
