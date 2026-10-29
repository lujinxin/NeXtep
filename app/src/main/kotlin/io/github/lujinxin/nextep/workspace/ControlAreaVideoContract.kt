package io.github.lujinxin.nextep.workspace

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    const val REASON_NOT_READY = "not_ready"
    const val HOST_PERMISSION = "android.permission.STATUS_BAR_SERVICE"
    const val EXTRA_SESSION = "session"
    const val EXTRA_SURFACE = "surface"
    const val EXTRA_WIDTH = "width"
    const val EXTRA_HEIGHT = "height"
    const val EXTRA_ACTIVE = "active"
    const val EXTRA_FILL_VIEWPORT = "fill_viewport"
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
    ) {
        context.sendBroadcast(request(context, session, ACTION_HOST)
            .putExtra(EXTRA_SURFACE, surface)
            .putExtra(EXTRA_WIDTH, width)
            .putExtra(EXTRA_HEIGHT, height)
            .putExtra(EXTRA_ACTIVE, active)
            .putExtra(EXTRA_FILL_VIEWPORT, fillViewport), HOST_PERMISSION)
    }

    fun sendCommand(context: Context, session: IBinder, command: String, sourceId: Long = 0L) {
        require(command in setOf(COMMAND_CAPTURE, COMMAND_RELEASE, COMMAND_PLAY, COMMAND_PAUSE, COMMAND_TOGGLE_PLAYBACK, COMMAND_CLOSE))
        context.sendBroadcast(request(context, session, ACTION_COMMAND)
            .putExtra(EXTRA_COMMAND, command)
            .putExtra(EXTRA_SOURCE_ID, sourceId), HOST_PERMISSION)
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
)
