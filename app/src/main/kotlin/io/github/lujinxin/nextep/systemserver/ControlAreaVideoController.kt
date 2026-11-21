package io.github.lujinxin.nextep.systemserver

import android.app.KeyguardManager
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Point
import android.graphics.Rect
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.view.KeyEvent
import android.view.SurfaceControl
import android.view.WindowManager
import io.github.lujinxin.nextep.framework.ActivityTaskManagerCompat
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.ControlAreaVideoContract
import io.github.lujinxin.nextep.workspace.ControlAreaVideoGeometry
import io.github.lujinxin.nextep.workspace.ControlAreaVideoState
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/** Own only the dock leash; the application's surfaces, playback and native geometry stay native. */
internal object ControlAreaVideoController {
    private data class Host(
        val session: IBinder,
        val surface: SurfaceControl?,
        val width: Int,
        val height: Int,
        val active: Boolean,
        val fillViewport: Boolean,
        val nativeInput: Boolean,
        val controlsVisible: Boolean,
        val death: IBinder.DeathRecipient,
    )

    private data class Source(
        val container: Any,
        val surfaceOwner: Any,
        val surface: SurfaceControl,
        val pipChrome: SurfaceControl?,
        val packageName: String,
        val pip: Boolean,
        val task: Boolean,
        val bounds: Rect,
        val windows: List<Any>,
        val secure: Boolean,
        val ownerUid: Int?,
    )

    private data class Embedded(val source: Source, val dock: SurfaceControl, val fallbackParent: SurfaceControl,
        val chrome: NativeVideoWindowControls.ChromeLease = NativeVideoWindowControls.ChromeLease())
    private data class NativePlayback(val source: Source, val controls: NativeVideoWindowControls.Controls)
    // A generic toggle label does not report the current playback state.
    private data class PipPlayback(val action: RemoteAction, val playing: Boolean?)
    private data class NativeProbe(val session: IBinder, val source: Source, val client: Any,
        val chrome: NativeVideoWindowControls.ChromeLease)

    private data class Playback(
        val controller: MediaController,
        val packageName: String,
        val ownerUid: Int?,
        val state: PlaybackState,
        val durationMs: Long,
        val mediaId: String?,
    ) {
        val playing: Boolean get() = state.state in setOf(PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING)
        val canControl: Boolean get() = state.actions and (PlaybackState.ACTION_PLAY_PAUSE or
            (if (playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY)) != 0L
        val canSeek: Boolean get() = durationMs > 0L && state.position >= 0L &&
            (state.actions and PlaybackState.ACTION_SEEK_TO) != 0L
        val advancing: Boolean get() = state.state in setOf(PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val starting = AtomicBoolean(false)
    private val videoPackages = ConcurrentHashMap<String, Boolean>()
    private val packageQueries = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var mediaPackages: Set<String> = emptySet()
    @Volatile private var mediaPlayback: List<Playback> = emptyList()
    @Volatile private var inputHandles: List<Any> = emptyList()
    @Volatile private var pipEmbedded = false
    @Volatile private var nativeInputCrop: SurfaceControl? = null
    @Volatile private var systemUiUid: Int? = null
    private var service: Any? = null
    private var context: Context? = null
    private var host: Host? = null
    private val retiredSessions = LinkedHashSet<IBinder>()
    private var embedded: Embedded? = null
    private var floatingPip: Source? = null
    private var closingSource: Source? = null
    @Volatile private var nativePlayback: NativePlayback? = null
    private var nativePlaybackProbePending = false
    private var nativePlaybackCheckedAt = 0L
    private var captureRequested = false
    private var requestedSourceId = 0L
    private var rejectedSource: Source? = null
    private var rejectionReason: String? = null
    private var observedSource: WeakReference<Any>? = null
    private var observedSourceSurface: WeakReference<SurfaceControl>? = null
    private var observedSourceWasPip = false
    private var sourceGeneration = 0L
    private var lastState: ControlAreaVideoState? = null
    private var lastDiagnosticState: String? = null
    private var failureReported = false
    private val mediaRefresh = Runnable { refreshMediaPackages() }

    private val releaseToken = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != IBinder.FIRST_CALL_TRANSACTION && code != ControlAreaVideoContract.OPEN_PIP_TRANSACTION) {
                return super.onTransact(code, data, reply, flags)
            }
            data.enforceInterface(ControlAreaVideoContract.RELEASE_DESCRIPTOR)
            val caller = Binder.getCallingUid()
            val owner = data.readStrongBinder()
            val expectedUid = systemUiUid
            if (code == ControlAreaVideoContract.OPEN_PIP_TRANSACTION) {
                val taskId = data.readInt()
                val opened = expectedUid != null && caller == expectedUid && owner != null &&
                    runCatching {
                        val identity = Binder.clearCallingIdentity()
                        try {
                            withWindowLock { openDockedPip(owner, taskId) } == true
                        } finally {
                            Binder.restoreCallingIdentity(identity)
                        }
                    }
                        .onFailure { NeXtepLog.warn("control_video", "Unable to expand docked PiP", it) }
                        .getOrDefault(false)
                reply?.writeNoException()
                reply?.writeInt(if (opened) 1 else 0)
                return true
            }
            val committed = CountDownLatch(1)
            val released = if (expectedUid != null && caller == expectedUid) withWindowLock {
                when {
                    owner != null && host?.session == owner -> {
                        captureRequested = false
                        // Even an already restored source may have an outstanding
                        // transaction; confirm every release through the compositor.
                        restoreNow { committed.countDown() }
                        host = host?.copy(active = false)
                        requestTraversal()
                        true
                    }
                    owner != null && owner in retiredSessions -> {
                        // A replaced host was restored before the new owner took over.
                        // Finish the old teardown without touching the new player's source.
                        SurfaceControl.Transaction().use { transaction ->
                            transaction.addTransactionCommittedListener(Executor { it.run() }) {
                                committed.countDown()
                            }
                            transaction.apply()
                        }
                        true
                    }
                    else -> false
                }
            } ?: false else false
            // The compositor callback uses its Binder thread, never the blocked SystemUI
            // caller. Waiting here must remain outside the WM global lock.
            val confirmed = released && committed.await(500, TimeUnit.MILLISECONDS)
            reply?.writeNoException()
            reply?.writeInt(if (confirmed) 1 else 0)
            return true
        }
    }

    fun ownsInputHandle(handle: Any): Boolean = inputHandles.any { it === handle }
    fun isPipEmbedded(): Boolean = pipEmbedded
    fun inputCrop(handle: Any): SurfaceControl? = nativeInputCrop?.takeIf { ownsInputHandle(handle) }
    fun ownsTask(task: Any): Boolean = embedded?.source?.let { it.task && it.container === task } == true

    private fun openDockedPip(session: IBinder, taskId: Int): Boolean {
        if (host?.session != session || host?.active != true || taskId <= 0) return false
        val docked = embedded?.source
        for (source in listOfNotNull(docked, floatingPip)) {
            if (!source.pip || !isPip(source.container)) continue
            val activity = if (source.surfaceOwner !== source.container) source.surfaceOwner else
                ControlVideoReflect.call(source.container, "getTopNonFinishingActivity")
            val leaf = activity?.let { ControlVideoReflect.call(it, "getTask") }
            if (ControlVideoReflect.field(source.container, "mTaskId") != taskId &&
                leaf?.let { ControlVideoReflect.field(it, "mTaskId") } != taskId) continue
            return expandPip(source, activity, docked === source)
        }
        return false
    }

    private fun expandPip(source: Source, activity: Any?, docked: Boolean): Boolean {
        if (docked || embedded == null) captureRequested = false
        try {
            // Put the visual surface back before Shell starts the native PiP exit.
            if (docked) restoreNow()
            floatingPip = null
            ControlAreaVideoInputHook.setFloatingPipInputAnchor(null)
            ControlVideoReflect.callRequired(source.container, "dismissPip")
            forgetExitedPip()
            val destination = activity?.let { ControlVideoReflect.call(it, "getTask") } ?: source.container
            ControlVideoReflect.call(destination, "moveToFront", "NeXtep open docked PiP")
            return true
        } finally {
            lastState = null
            requestTraversal()
        }
    }

    fun onInputFailure(transaction: SurfaceControl.Transaction?) {
        withWindowLock {
            captureRequested = false
            if (transaction != null) restore(transaction) else restoreNow()
            requestTraversal()
        }
    }

    /** Called at the end of DisplayContent.prepareSurfaces under the existing WM global lock. */
    fun prepare(
        display: Any,
        transaction: SurfaceControl.Transaction,
        nativeLayers: NativeFloatingWindowLayerHooker,
    ) {
        if (ControlVideoReflect.call(display, "getDisplayId") != 0) return
        initialize(display)
        forgetExitedPip()
        val currentHost = host
        if (currentHost?.active != true || currentHost.surface?.isValid != true ||
            currentHost.width <= 1 || currentHost.height <= 1 ||
            SystemServerWorkspaceBridge.observedActiveGeometry() == null) {
            restore(transaction)
            return
        }
        runCatching {
            val candidates = findSources(display)
            val candidate = candidates.firstOrNull()
            val rejected = rejectedSource
            if (rejectionReason != null && (rejected?.container !== candidate?.container ||
                    (rejected != null && !sameSurface(rejected.surface, candidate?.surface)))) {
                rejectedSource = null
                rejectionReason = null
            }
            val current = embedded
            if (current != null) {
                val refreshed = candidates.firstOrNull { it.container === current.source.container &&
                    sameSurface(it.surface, current.source.surface) }
                if (refreshed == null || refreshed.secure) {
                    restore(transaction)
                    captureRequested = false
                } else {
                    // A display rotation also puts an existing source in a native
                    // transition. Keep its dock and ownership while that same valid
                    // window remains visible; restoring here exposes the floating
                    // window until SystemUI's delayed automatic capture runs again.
                    // Only acquiring a new source must wait for transition end.
                    embedded = current.copy(source = refreshed)
                }
            }
            var reason: String? = rejectionReason
            if (captureRequested && embedded == null) {
                captureRequested = false
                val source = candidates.firstOrNull()
                // A delayed automatic request must not capture a different native window.
                when {
                    requestedSourceId > 0L && requestedSourceId != sourceId(source) -> Unit
                    !ControlAreaVideoInputHook.isReady() -> reason = "unsupported"
                    source == null -> reason = "no_source"
                    source.secure -> reason = "secure"
                    inTransition(source) -> reason = "transitioning"
                    else -> {
                        val parent = nativeParent(source.surfaceOwner)?.takeIf { it.isValid }
                        if (parent == null) reason = ControlAreaVideoContract.REASON_NOT_READY else {
                            val dock = newDock(checkNotNull(currentHost.surface))
                            embedded = Embedded(source, dock, parent)
                        }
                    }
                }
                if (reason == "unsupported") {
                    rejectedSource = source
                    rejectionReason = reason
                }
            }
            embedded?.let { currentEmbedded ->
                val source = currentEmbedded.source
                inputHandles = inputHandles(source.windows)
                pipEmbedded = source.pip
                // App overlays own their player controls. System PiP uses a separate Shell
                // input consumer; keep that path on NeXtep's controls rather than routing
                // raw gestures into a pinned Activity which may have no player chrome.
                nativeInputCrop = currentEmbedded.dock.takeIf {
                    currentHost.nativeInput && !source.pip && ControlAreaVideoInputHook.canInteract(inputHandles)
                }
                if (!ControlAreaVideoInputHook.suppressCurrent(transaction, source.windows, display)) {
                    restore(transaction)
                    // WindowState can exist before its animator/input surface is ready.
                    // Keep hard hook failures terminal, but do not latch an ordinary race.
                    reason = if (ControlAreaVideoInputHook.isReady()) {
                        ControlAreaVideoContract.REASON_NOT_READY
                    } else "unsupported"
                    if (reason == "unsupported") {
                        rejectedSource = source
                        rejectionReason = reason
                    }
                } else {
                    applyDock(currentEmbedded, currentHost, transaction)
                }
            }
            val nativePip = candidates.firstOrNull { it.pip && !ownsTask(it.container) }
            floatingPip = nativePip
            updateFloatingPipInputOrder(nativePip, display, transaction)
            val selected = embedded?.source ?: candidates.firstOrNull()
            val playback = selected?.let(::playbackFor)
            val pipPlayback = selected?.let(::pipPlaybackFor)
            val nativePlaybackAvailable = selected?.let(::hasNativePlayback) == true
            publish(ControlAreaVideoState(
                available = selected != null,
                embedded = embedded != null,
                packageName = selected?.packageName,
                sourceId = sourceId(selected),
                reason = reason ?: when {
                    selected?.secure == true -> "secure"
                    embedded == null && selected != null && inTransition(selected) -> "transitioning"
                    else -> null
                },
                session = currentHost.session,
                releaseToken = releaseToken,
                playing = pipPlayback?.playing ?: playback?.playing ?: true,
                canControlPlayback = pipPlayback != null || playback?.canControl == true || nativePlaybackAvailable,
                playbackToggleOnly = nativePlaybackAvailable || (pipPlayback != null && pipPlayback.playing == null),
                nativeInput = embedded != null && nativeInputCrop != null,
                sourceWidth = selected?.bounds?.width() ?: 0,
                sourceHeight = selected?.bounds?.height() ?: 0,
                nativeSeekAvailable = nativeInputCrop != null && selected?.let(::nativeControlsFor)?.seekable == true,
                canSeek = playback?.canSeek == true,
                durationMs = playback?.durationMs ?: 0L,
                positionMs = playback?.state?.position ?: -1L,
                positionUpdatedAt = playback?.state?.lastPositionUpdateTime ?: 0L,
                playbackSpeed = if (playback?.advancing == true) playback.state.playbackSpeed else 0f,
                mediaSessionToken = playback?.controller?.sessionToken,
                mediaId = playback?.mediaId,
                nativeCloseBounds = selected?.let(::nativeControlsFor)?.replacedClose,
                nativeFullscreenBounds = selected?.let(::nativeControlsFor)?.replacedFullscreen,
                floatingPipBounds = nativePip?.bounds?.let(::Rect),
                floatingPipLayer = nativePip?.let { nativeLayers.pinnedMotionLayer(it.container) },
                sourceIsPip = selected?.pip == true,
            ))
        }.onFailure { error ->
            runCatching { restore(transaction) }
            captureRequested = false
            publish(ControlAreaVideoState(reason = "unsupported", session = currentHost.session,
                releaseToken = releaseToken))
            if (!failureReported) {
                failureReported = true
                NeXtepLog.warn("control_video", "Video embedding unavailable; retaining native window", error)
            }
        }
    }

    private fun initialize(display: Any) {
        if (starting.get()) return
        val wm = ControlVideoReflect.field(display, "mWmService") ?: return
        val systemContext = ControlVideoReflect.field(wm, "mContext") as? Context ?: return
        if (!starting.compareAndSet(false, true)) return
        service = wm
        context = systemContext
        SystemServerWorkspaceBridge.startGeometryObservation(systemContext)
        handler.post {
            runCatching {
                systemUiUid = systemContext.packageManager.getApplicationInfo("com.android.systemui", 0).uid
                systemContext.registerReceiver(receiver, IntentFilter().apply {
                    addAction(ControlAreaVideoContract.ACTION_HOST)
                    addAction(ControlAreaVideoContract.ACTION_COMMAND)
                    addAction(Intent.ACTION_SCREEN_OFF)
                }, ControlAreaVideoContract.HOST_PERMISSION, handler, Context.RECEIVER_EXPORTED)
            }.onFailure {
                starting.set(false)
                NeXtepLog.warn("control_video", "Video host bridge unavailable", it)
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                runCatching {
                    withWindowLock {
                        captureRequested = false
                        restoreNow()
                        host = host?.copy(active = false)
                        requestTraversal()
                    }
                }.onFailure { NeXtepLog.warn("control_video", "Screen-off video restoration unavailable", it) }
                return
            }
            if (!ControlAreaVideoContract.trustedHost(context, intent)) return
            val session = intent.extras?.getBinder(ControlAreaVideoContract.EXTRA_SESSION) ?: return
            val surface = intent.getParcelableExtra(ControlAreaVideoContract.EXTRA_SURFACE, SurfaceControl::class.java)
            runCatching {
                val command = intent.getStringExtra(ControlAreaVideoContract.EXTRA_COMMAND)
                if (intent.action == ControlAreaVideoContract.ACTION_COMMAND && command in setOf(
                        ControlAreaVideoContract.COMMAND_PLAY, ControlAreaVideoContract.COMMAND_PAUSE,
                        ControlAreaVideoContract.COMMAND_TOGGLE_PLAYBACK,
                    ControlAreaVideoContract.COMMAND_CLOSE, ControlAreaVideoContract.COMMAND_SEEK,
                    ControlAreaVideoContract.COMMAND_FULLSCREEN)) {
                    handlePlaybackCommand(session, intent.getLongExtra(ControlAreaVideoContract.EXTRA_SOURCE_ID, 0L),
                        command, intent.getLongExtra(ControlAreaVideoContract.EXTRA_POSITION_MS, -1L),
                        intent.getParcelableExtra(ControlAreaVideoContract.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java),
                        intent.getLongExtra(ControlAreaVideoContract.EXTRA_DURATION_MS, 0L),
                        intent.getStringExtra(ControlAreaVideoContract.EXTRA_MEDIA_ID))
                    return
                }
                withWindowLock {
                    if (session in retiredSessions) {
                        surface?.release()
                        return@withWindowLock
                    }
                    when (intent.action) {
                        ControlAreaVideoContract.ACTION_HOST -> updateHost(intent, session, surface)
                        ControlAreaVideoContract.ACTION_COMMAND -> {
                            if (host?.session != session || host?.active != true) return@withWindowLock
                            when (intent.getStringExtra(ControlAreaVideoContract.EXTRA_COMMAND)) {
                                ControlAreaVideoContract.COMMAND_CAPTURE -> {
                                    rejectionReason = null
                                    rejectedSource = null
                                    requestedSourceId = intent.getLongExtra(ControlAreaVideoContract.EXTRA_SOURCE_ID, 0L)
                                    captureRequested = true
                                }
                                ControlAreaVideoContract.COMMAND_RELEASE -> {
                                    captureRequested = false
                                    rejectionReason = null
                                    rejectedSource = null
                                    restoreNow()
                                }
                            }
                        }
                    }
                    lastState = null
                    requestTraversal()
                }
            }.onFailure { NeXtepLog.warn("control_video", "Video host request failed open", it) }
        }
    }

    private fun handlePlaybackCommand(
        session: IBinder,
        requestedId: Long,
        command: String?,
        positionMs: Long,
        expectedMediaSession: MediaSession.Token?,
        expectedDurationMs: Long,
        expectedMediaId: String?,
    ) {
        val source = withWindowLock {
            embedded?.source?.takeIf { host?.session == session && host?.active == true &&
                requestedId > 0L && sourceId(it) == requestedId }
        } ?: return
        if (command == ControlAreaVideoContract.COMMAND_SEEK) refreshMediaPackages()
        val playback = playbackFor(source)
        if (command == ControlAreaVideoContract.COMMAND_CLOSE) {
            closeNativeSource(session, source)
        } else if (command == ControlAreaVideoContract.COMMAND_FULLSCREEN) {
            openNativeFullscreen(session, source)
        } else if (command == ControlAreaVideoContract.COMMAND_SEEK) {
            if (playback == null || !seekPlayback(session, source, playback, expectedMediaSession,
                    expectedDurationMs, expectedMediaId, positionMs)) return
        } else if (command == ControlAreaVideoContract.COMMAND_TOGGLE_PLAYBACK && hasNativePlayback(source)) {
            val client = ControlVideoReflect.field(source.container, "mClient") ?: return
            NativeVideoWindowControls.request(
                NativeVideoWindowControls.Action.TOGGLE_PLAYBACK,
                isCurrent = { isCurrentSource(session, source) },
                execute = { name, parameters, output ->
                    ControlVideoReflect.callRequired(client, "executeCommand", name, parameters, output)
                },
                onFinished = { result ->
                    if (result.exceptionOrNull() is NativeVideoWindowControls.NotInvokedException) {
                        handler.post { useMediaPlaybackFallback(session, source, expectedMediaSession) }
                    } else {
                        result.onFailure { NeXtepLog.warn("control_video", "Native playback click returned no confirmation", it) }
                    }
                },
            )
        } else if (!dispatchPipPlayback(session, source, command) && playback?.canControl == true) {
            val requiresSameSession = expectedMediaSession != null ||
                (source.pip && command == ControlAreaVideoContract.COMMAND_TOGGLE_PLAYBACK)
            if (requiresSameSession && playback.controller.sessionToken != expectedMediaSession) return
            if (!isCurrentSource(session, source)) return
            val playing = when (command) {
                ControlAreaVideoContract.COMMAND_PLAY -> true
                ControlAreaVideoContract.COMMAND_PAUSE -> false
                else -> !playback.playing
            }
            setPlayback(playback, playing)
        }
        // Read back the app's state after it has handled the transport command.
        handler.removeCallbacks(mediaRefresh)
        handler.postDelayed(mediaRefresh, 150L)
    }

    private fun openNativeFullscreen(session: IBinder, source: Source) {
        if (source.task || nativeControlsFor(source)?.fullscreen == null) return
        val client = ControlVideoReflect.field(source.container, "mClient") ?: return
        // Keep the dock attached while the owner opens its player and removes the
        // float. Restoring first would briefly expose a separate floating window.
        NativeVideoWindowControls.request(NativeVideoWindowControls.Action.FULLSCREEN,
            isCurrent = { isCurrentSource(session, source) },
            execute = { name, parameters, output ->
                ControlVideoReflect.callRequired(client, "executeCommand", name, parameters, output)
            },
            onFinished = { result ->
                result.onFailure { NeXtepLog.warn("control_video", "Native fullscreen action was not confirmed", it) }
                handler.post { withWindowLock { lastState = null; requestTraversal() } }
            },
        )
    }

    private fun closeNativeSource(session: IBinder, source: Source) {
        val accepted = withWindowLock {
            if (closingSource != null) false else { closingSource = source; true }
        } == true
        if (!accepted) return
        if (source.task) {
            try {
                val taskId = checkNotNull(ControlVideoReflect.field(source.container, "mTaskId") as? Int)
                // PiP task removal runs the activity lifecycle. App overlay removal
                // does not, so overlays must execute their owner's close listener.
                ActivityTaskManagerCompat.removeTask(taskId).getOrThrow()
            } finally {
                withWindowLock { closingSource = null; requestTraversal() }
            }
            return
        }
        val client = ControlVideoReflect.field(source.container, "mClient")
        if (client == null) {
            withWindowLock { closingSource = null }
            return
        }
        NativeVideoWindowClose.request(
            isCurrent = {
                withWindowLock {
                    host?.session == session && host?.active == true &&
                        embedded?.source?.container === source.container &&
                        sameSurface(source.surface, embedded?.source?.surface)
                } == true
            },
            execute = { name, parameters, output ->
                ControlVideoReflect.callRequired(client, "executeCommand", name, parameters, output)
            },
            onFinished = { result -> handler.postDelayed({
                withWindowLock {
                    // Some framework versions fail to serialize performClick's
                    // boolean return after the listener already closed the window.
                    // The owner's window lifecycle is the authoritative result.
                    if (source.surface.isValid && visible(source.container)) {
                        result.onFailure { NeXtepLog.warn("control_video", "Native video close unavailable; keeping source visible", it) }
                    }
                    if (closingSource === source) closingSource = null
                    lastState = null
                    // Wait for the owner to remove its window. Never manufacture a
                    // successful close by deleting the borrowed surface or pausing it.
                    requestTraversal()
                }
                handler.removeCallbacks(mediaRefresh)
                handler.postDelayed(mediaRefresh, 150L)
            }, 150L) },
        )
    }

    private fun setPlayback(playback: Playback, playing: Boolean) {
        val controller = playback.controller
        val current = playback.copy(state = controller.playbackState ?: return)
        if (current.playing == playing) return
        val action = if (playing) PlaybackState.ACTION_PLAY else PlaybackState.ACTION_PAUSE
        if (current.state.actions and action != 0L) {
            if (playing) controller.transportControls.play() else controller.transportControls.pause()
        } else if (current.state.actions and PlaybackState.ACTION_PLAY_PAUSE != 0L) {
            controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        }
    }

    private fun dispatchPipPlayback(session: IBinder, source: Source, command: String?): Boolean {
        if (!source.pip) return false
        val playback = withWindowLock {
            if (isCurrentSource(session, source)) pipPlaybackFor(source) else null
        } ?: return false
        val requestedPlaying = when (command) {
            ControlAreaVideoContract.COMMAND_PLAY -> true
            ControlAreaVideoContract.COMMAND_PAUSE -> false
            else -> null
        }
        if (requestedPlaying != null) {
            val playing = playback.playing ?: return false
            if (requestedPlaying == playing) return true
        }
        return runCatching {
            playback.action.actionIntent.send()
        }.onFailure { NeXtepLog.warn("control_video", "Native PiP playback action failed", it) }.isSuccess
    }

    private fun pipPlaybackFor(source: Source): PipPlayback? {
        if (!source.pip) return null
        val activity = if (source.surfaceOwner !== source.container) source.surfaceOwner
            else ControlVideoReflect.call(source.container, "getTopNonFinishingActivity") ?: return null
        val params = ControlVideoReflect.field(activity, "pictureInPictureArgs")
            ?: ControlVideoReflect.call(activity, "getPictureInPictureArgs") ?: return null
        val actions = ControlVideoReflect.call(params, "getActions") as? List<*> ?: return null
        // PiP apps may publish working transport actions while their MediaSession reports NONE.
        // Match only an unambiguous play/pause action supplied by this player's own app.
        val candidates = actions.mapNotNull { action ->
            if (action !is RemoteAction || !action.isEnabled || action.actionIntent.creatorPackage != source.packageName ||
                (source.ownerUid != null && action.actionIntent.creatorUid != source.ownerUid)) return@mapNotNull null
            val labels = listOf(action.title, action.contentDescription).map { it.toString().trim().lowercase(Locale.ROOT) }
            val canPause = labels.any { it in pipPauseLabels }
            val canPlay = labels.any { it in pipPlayLabels }
            when {
                canPause && canPlay -> null
                canPause || canPlay -> PipPlayback(action, playing = canPause)
                labels.any { it.replace(pipToggleSeparator, "/") in pipToggleLabels } ->
                    PipPlayback(action, playing = null)
                else -> null
            }
        }
        val directional = candidates.filter { it.playing != null }
        return directional.singleOrNull() ?: candidates.filter { it.playing == null }.singleOrNull()
    }

    private val pipPlayLabels = setOf("play", "play video", "resume", "resume video", "resume playback", "播放",
        "播放视频", "播放影片", "继续播放", "繼續播放", "恢复播放", "恢復播放", "再生", "재생")
    private val pipPauseLabels = setOf("pause", "pause video", "pause playback", "暂停", "暫停", "暂停播放",
        "暫停播放", "暂停视频", "暂停影片", "暫停影片", "一時停止", "일시중지", "일시 중지")
    private val pipToggleSeparator = Regex("""\s*[/／、]\s*""")
    private val pipToggleLabels = setOf("play/pause", "pause/play", "toggle playback", "播放/暂停", "暂停/播放",
        "播放/暫停", "暫停/播放", "播放/暂停播放", "播放/暫停播放", "再生/一時停止", "재생/일시중지", "재생/일시 중지")

    private fun useMediaPlaybackFallback(session: IBinder, source: Source, expectedMediaSession: MediaSession.Token?) {
        if (!isCurrentSource(session, source)) return
        withWindowLock {
            nativePlayback?.takeIf { it.source.container === source.container &&
                sameSurface(it.source.surface, source.surface) }?.let {
                nativePlayback = it.copy(controls = it.controls.copy(playbackToggle = null))
            }
            lastState = null
            requestTraversal()
        }
        runCatching {
            refreshMediaPackages()
            val fallback = playbackFor(source) ?: return@runCatching
            if (expectedMediaSession == null || fallback.controller.sessionToken != expectedMediaSession) return@runCatching
            val current = fallback.copy(state = fallback.controller.playbackState ?: return@runCatching)
            if (current.canControl && isCurrentSource(session, source)) setPlayback(current, !current.playing)
        }.onFailure { NeXtepLog.warn("control_video", "Media playback fallback failed", it) }
        handler.removeCallbacks(mediaRefresh)
        handler.postDelayed(mediaRefresh, 150L)
    }

    private fun seekPlayback(
        session: IBinder,
        source: Source,
        playback: Playback,
        expectedMediaSession: MediaSession.Token?,
        expectedDurationMs: Long,
        expectedMediaId: String?,
        positionMs: Long,
    ): Boolean {
        if (positionMs < 0L || expectedMediaSession == null ||
            playback.controller.sessionToken != expectedMediaSession) return false
        // Refresh capabilities outside the WM lock. Ignore scrubs after a source,
        // session or identifiable media-item change.
        val controller = playback.controller
        val currentState = controller.playbackState ?: return false
        val metadata = controller.metadata
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf { it.isNotBlank() }
        if (duration <= 0L || duration != expectedDurationMs || mediaId != expectedMediaId || currentState.position < 0L ||
            (currentState.actions and PlaybackState.ACTION_SEEK_TO) == 0L ||
            !isCurrentSource(session, source) || playbackFor(source)?.controller?.sessionToken != expectedMediaSession) return false
        controller.transportControls.seekTo(positionMs.coerceIn(0L, duration))
        return true
    }

    private fun playbackFor(source: Source): Playback? {
        val matching = mediaPlayback.filter {
            it.packageName == source.packageName && (source.ownerUid == null || it.ownerUid == source.ownerUid)
        }.distinctBy { it.controller.sessionToken }
        // Multiple indistinguishable sessions from one app are not a reliable target.
        if (matching.size == 1) return matching.first()
        val playing = matching.filter { it.playing }
        if (playing.isNotEmpty()) return playing.singleOrNull()
        return matching.filter { it.state.state == PlaybackState.STATE_PAUSED }.singleOrNull()
    }

    private fun isCurrentSource(session: IBinder, source: Source): Boolean = withWindowLock {
        host?.session == session && host?.active == true && embedded?.source?.container === source.container &&
            sameSurface(source.surface, embedded?.source?.surface)
    } == true

    private fun nativeControlsFor(source: Source): NativeVideoWindowControls.Controls? = nativePlayback?.takeIf {
        it.source.container === source.container && sameSurface(it.source.surface, source.surface)
    }?.controls

    private fun hasNativePlayback(source: Source): Boolean = nativeControlsFor(source)?.playbackToggle != null

    /** Discover on the worker, never while a window traversal holds the WM global lock. */
    private fun probeNativePlayback(force: Boolean = false) {
        val request = withWindowLock {
            val current = embedded ?: return@withWindowLock null
            val source = current.source
            val session = host?.session ?: return@withWindowLock null
            if (source.task || nativePlaybackProbePending) return@withWindowLock null
            val cached = nativePlayback?.takeIf {
                it.source.container === source.container && sameSurface(it.source.surface, source.surface)
            }
            if (!force && cached != null && cached.source.bounds == source.bounds &&
                SystemClock.uptimeMillis() - nativePlaybackCheckedAt < 10_000L) {
                return@withWindowLock null
            }
            val client = ControlVideoReflect.field(source.container, "mClient") ?: return@withWindowLock null
            nativePlaybackProbePending = true
            NativeProbe(session, source, client, current.chrome)
        } ?: return
        val (session, source, client, chrome) = request
        NativeVideoWindowControls.inspect(
            isCurrent = { isCurrentSource(session, source) },
            execute = { name, parameters, output ->
                ControlVideoReflect.callRequired(client, "executeCommand", name, parameters, output)
            },
            chrome = chrome,
            onFinished = { result -> handler.post {
                withWindowLock {
                    nativePlaybackProbePending = false
                    if (isCurrentSource(session, source)) {
                        nativePlayback = NativePlayback(source, result.getOrNull()
                            ?: nativeControlsFor(source) ?: NativeVideoWindowControls.Controls())
                        nativePlaybackCheckedAt = SystemClock.uptimeMillis()
                        lastState = null
                        requestTraversal()
                    }
                }
            } },
        )
    }

    private fun updateHost(intent: Intent, session: IBinder, surface: SurfaceControl?) {
        val previous = host
        val sameOwner = previous?.session == session
        val active = intent.getBooleanExtra(ControlAreaVideoContract.EXTRA_ACTIVE, false) &&
            context?.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
        val width = intent.getIntExtra(ControlAreaVideoContract.EXTRA_WIDTH, 0).coerceIn(0, 16384)
        val height = intent.getIntExtra(ControlAreaVideoContract.EXTRA_HEIGHT, 0).coerceIn(0, 16384)
        val fillViewport = intent.getBooleanExtra(ControlAreaVideoContract.EXTRA_FILL_VIEWPORT, false)
        val nativeInput = active && intent.getBooleanExtra(ControlAreaVideoContract.EXTRA_NATIVE_INPUT, false)
        val controlsVisible = active && intent.getBooleanExtra(ControlAreaVideoContract.EXTRA_CONTROLS_VISIBLE, false)
        val changedSurface = previous?.surface?.let { !sameSurface(it, surface) } == true
        if (!sameOwner || !active || changedSurface) {
            captureRequested = false
            rejectedSource = null
            rejectionReason = null
            restoreNow()
        }
        if (!sameOwner && previous != null) {
            retiredSessions.add(previous.session)
            if (retiredSessions.size > 128) retiredSessions.remove(retiredSessions.first())
            runCatching { previous.session.unlinkToDeath(previous.death, 0) }
        }
        val death = if (sameOwner) checkNotNull(previous).death else IBinder.DeathRecipient {
            handler.post {
                runCatching {
                    withWindowLock {
                        if (host?.session == session) {
                            restoreNow()
                            host?.surface?.release()
                            host = null
                            captureRequested = false
                            requestTraversal()
                        }
                    }
                }.onFailure {
                    NeXtepLog.warn("control_video", "Dead video host restoration unavailable", it)
                }
            }
        }
        if (!sameOwner) session.linkToDeath(death, 0)
        host = Host(session, surface, width, height, active, fillViewport, nativeInput, controlsVisible, death)
        previous?.surface?.takeIf { it !== surface }?.release()
        handler.removeCallbacks(mediaRefresh)
        if (active) handler.post(mediaRefresh)
        if (controlsVisible && previous?.controlsVisible != true) handler.post { probeNativePlayback(force = true) }
        if (!active) publish(ControlAreaVideoState(session = session, releaseToken = releaseToken))
    }

    private fun applyDock(current: Embedded, target: Host, transaction: SurfaceControl.Transaction) {
        val source = current.source
        val position = if (source.pip && source.surfaceOwner === source.container) {
            // Shell positions the pinned task leash at its PiP bounds. The WindowContainer
            // position cache can still be (0, 0) for the organized task.
            Point(source.bounds.left, source.bounds.top)
        } else {
            (ControlVideoReflect.field(source.surfaceOwner, "mLastSurfacePosition") as? Point)
                ?.let(::Point) ?: Point().also { ControlVideoReflect.call(source.surfaceOwner, "getRelativePosition", it) }
        }
        if (!source.task) {
            // WindowState positions the surface before its transparent/shadow insets,
            // while getFrame() describes only the window content. Fit and crop from
            // that content origin; otherwise scaling also magnifies the inset offset.
            val insets = checkNotNull(ControlVideoReflect.field(checkNotNull(attrs(source.container)), "surfaceInsets") as? Rect)
            if (insets.left != 0 || insets.top != 0) {
                val contentOffset = Point()
                ControlVideoReflect.callRequired(source.container, "transformSurfaceInsetsPosition", contentOffset, insets)
                position.offset(contentOffset.x, contentOffset.y)
            }
        }
        val width = source.bounds.width().coerceAtLeast(1)
        val height = source.bounds.height().coerceAtLeast(1)
        val placement = ControlAreaVideoGeometry.place(width, height, target.width, target.height, target.fillViewport)
        val scale = placement.scale
        check(scale.isFinite() && scale > 0f)
        transaction.reparent(current.dock, checkNotNull(target.surface))
        transaction.reparent(source.surface, current.dock)
        source.pipChrome?.takeIf { it.isValid }?.let { transaction.setVisibility(it, false) }
        transaction.setLayer(source.surface, 0)
        transaction.setLayer(current.dock, 1)
        ControlVideoReflect.callRequired(transaction, "setMatrix", current.dock, scale, 0f, 0f, scale)
        transaction.setPosition(current.dock,
            placement.left - position.x * scale,
            placement.top - position.y * scale)
        transaction.setCrop(current.dock, Rect(position.x, position.y, position.x + width, position.y + height))
        transaction.setVisibility(current.dock, true)
    }

    private fun restore(transaction: SurfaceControl.Transaction) {
        val current = embedded
        if (current != null && current.source.surface.isValid) {
            val source = current.source
            val parent = nativeParent(source.surfaceOwner)?.takeIf { it.isValid }
                ?: current.fallbackParent.takeIf { it.isValid }
            // Keep ownership until the native reparent is queued, so a failed request
            // can retry without losing the only reference to the docked source.
            transaction.reparent(source.surface, parent)
            runCatching {
                val relative = (ControlVideoReflect.call(source.surfaceOwner, "getLastRelativeLayer")
                    ?: ControlVideoReflect.field(source.surfaceOwner, "mLastRelativeToLayer")) as? SurfaceControl
                val layer = (ControlVideoReflect.call(source.surfaceOwner, "getLastLayer")
                    ?: ControlVideoReflect.field(source.surfaceOwner, "mLastLayer")) as? Int ?: 0
                if (relative?.isValid == true) {
                    ControlVideoReflect.callRequired(transaction, "setRelativeLayer", source.surface, relative, layer)
                } else transaction.setLayer(source.surface, layer)
            }.getOrElse {
                // Optional private layer metadata must never cancel the restoration.
                transaction.setLayer(source.surface, 0)
            }
        }
        current?.source?.let { source ->
            source.pipChrome?.takeIf { it.isValid }?.let { transaction.setVisibility(it, visible(source.container)) }
        }
        current?.chrome?.close()
        embedded = null
        floatingPip = null
        nativeInputCrop = null
        nativePlayback = null
        inputHandles = emptyList()
        ControlAreaVideoInputHook.setFloatingPipInputAnchor(null, transaction = transaction)
        pipEmbedded = false
        ControlAreaVideoInputHook.restoreAll(transaction)
        if (current == null) return
        runCatching { if (current.dock.isValid) transaction.reparent(current.dock, null) }
        runCatching { current.dock.release() }
        runCatching { requestInputUpdate() }
    }

    private fun restoreNow(onCommitted: (() -> Unit)? = null) {
        SurfaceControl.Transaction().use { transaction ->
            restore(transaction)
            if (onCommitted != null) {
                transaction.addTransactionCommittedListener(Executor { it.run() }) { onCommitted() }
            }
            transaction.apply()
        }
    }

    private fun findSources(display: Any): List<Source> {
        val pinned = linkedMapOf<Any, MutableList<Any>>()
        val overlays = mutableListOf<Source>()
        fun addPinned(task: Any, window: Any? = null) {
            if (!isPip(task)) return
            val root = ControlVideoReflect.call(task, "getRootTask")?.takeIf(::isPip) ?: task
            val reportedWindows = pinned.getOrPut(root) { mutableListOf() }
            if (window != null) reportedWindows.add(window)
        }
        ControlVideoReflect.call(display, "getDefaultTaskDisplayArea")
            ?.let { ControlVideoReflect.call(it, "getRootPinnedTask") }
            ?.let { addPinned(it) }
        ControlVideoReflect.callRequired(display, "forAllWindows", Consumer<Any> { window ->
            val params = attrs(window) ?: return@Consumer
            if (!visible(window)) return@Consumer
            if (params.type != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
                // Follow the visible app window to its actual PiP task. This also covers
                // a pinned leaf whose outer task does not advertise pinned or visible.
                val activity = ControlVideoReflect.field(window, "mActivityRecord") ?: return@Consumer
                val task = ControlVideoReflect.call(window, "getTask")
                    ?: ControlVideoReflect.call(activity, "getTask") ?: return@Consumer
                addPinned(task, window)
                return@Consumer
            }
            val owner = params.packageName?.takeIf(::externalPackage) ?: return@Consumer
            if (!videoPackage(owner)) return@Consumer
            source(window, owner, false, false, listOf(window))?.let(overlays::add)
        }, true)
        if (pinned.isEmpty()) {
            ControlVideoReflect.call(display, "forAllTasks", Consumer<Any> { addPinned(it) }, true)
        }
        return pinned.mapNotNull { (task, reportedWindows) ->
            val taskWindows = (windows(task) + reportedWindows).distinct()
            val owner = taskWindows.firstNotNullOfOrNull { window ->
                attrs(window)?.takeUnless { it.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING }
                    ?.packageName?.takeIf(::externalPackage)
            } ?: return@mapNotNull null
            source(task, owner, true, true, taskWindows)
        } + overlays
    }

    private fun source(container: Any, owner: String, pip: Boolean, task: Boolean, windows: List<Any>): Source? {
        val nativeSurface = ControlVideoReflect.call(container, "getSurfaceControl") as? SurfaceControl ?: return null
        if (!nativeSurface.isValid || windows.isEmpty()) return null
        val bounds = ((if (task) ControlVideoReflect.call(container, "getBounds")
            else ControlVideoReflect.call(container, "getFrame")) as? Rect)?.let(::Rect) ?: return null
        if (bounds.width() <= 1 || bounds.height() <= 1) return null
        val secure = windows.any { window ->
            ControlVideoReflect.call(window, "isSecureLocked") == true ||
                (attrs(window)?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE != 0
        }
        val ownerUid = windows.firstNotNullOfOrNull { ControlVideoReflect.field(it, "mOwnerUid") as? Int }
        // The PiP Task leash owns the system corner radius and menu. Take just the
        // ActivityRecord surface when possible so the dock can keep square corners.
        if (pip) {
            val activity = windows.firstNotNullOfOrNull { window ->
                if (attrs(window)?.packageName == owner &&
                    attrs(window)?.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION) {
                    ControlVideoReflect.field(window, "mActivityRecord")
                } else null
            }
            val content = activity?.let { ControlVideoReflect.call(it, "getSurfaceControl") as? SurfaceControl }
            if (activity != null && content?.isValid == true && !sameSurface(nativeSurface, content)) {
                return Source(container, activity, content, nativeSurface, owner, pip, task, bounds, windows, secure, ownerUid)
            }
        }
        return Source(container, container, nativeSurface, null, owner, pip, task, bounds, windows, secure, ownerUid)
    }

    private fun windows(container: Any): List<Any> = mutableListOf<Any>().also { result ->
        ControlVideoReflect.call(container, "forAllWindows", Consumer<Any> { window ->
            if (visible(window)) result.add(window)
        }, true)
    }

    private fun attrs(window: Any): WindowManager.LayoutParams? =
        ControlVideoReflect.field(window, "mAttrs") as? WindowManager.LayoutParams

    private fun visible(container: Any): Boolean = ControlVideoReflect.call(container, "isVisible") == true ||
        ControlVideoReflect.call(container, "isVisibleRequested") == true

    private fun isPip(task: Any): Boolean = ControlVideoReflect.call(task, "inPinnedWindowingMode") == true ||
        ControlVideoReflect.call(task, "getWindowingMode") == 2

    private fun inTransition(container: Any): Boolean =
        ControlVideoReflect.call(container, "inTransition") == true ||
            (ControlVideoReflect.call(container, "getAnimationLeash") as? SurfaceControl)?.isValid == true

    private fun inTransition(source: Source): Boolean =
        inTransition(source.container) || (source.surfaceOwner !== source.container && inTransition(source.surfaceOwner))

    private fun nativeParent(container: Any): SurfaceControl? =
        (ControlVideoReflect.call(container, "getAnimationLeash") as? SurfaceControl)?.takeIf { it.isValid }
            ?: (ControlVideoReflect.call(container, "getParentSurfaceControl") as? SurfaceControl)
            ?: ControlVideoReflect.call(container, "getParent")?.let {
                ControlVideoReflect.call(it, "getSurfaceControl") as? SurfaceControl
            }

    private fun newDock(parent: SurfaceControl): SurfaceControl {
        val builder = Class.forName("android.view.SurfaceControl\$Builder").getDeclaredConstructor().newInstance()
        ControlVideoReflect.callRequired(builder, "setName", "NeXtep-ControlVideo-Dock")
        ControlVideoReflect.callRequired(builder, "setContainerLayer")
        ControlVideoReflect.callRequired(builder, "setParent", parent)
        return checkNotNull(ControlVideoReflect.callRequired(builder, "build") as? SurfaceControl)
    }

    private fun videoPackage(packageName: String): Boolean {
        // These apps do not necessarily publish a MediaSession/category for every video.
        // Package IDs: app.mi.com/details?id=tv.danmaku.bili and
        // sj.qq.com/appdetail/com.phoenix.read (official store listings).
        if (packageName in setOf("tv.danmaku.bili", "com.phoenix.read") ||
            packageName in mediaPackages || videoPackages[packageName] == true) return true
        if (!videoPackages.containsKey(packageName) && packageQueries.add(packageName)) handler.post {
            videoPackages[packageName] = runCatching {
                context?.packageManager?.getApplicationInfo(packageName, 0)?.category == ApplicationInfo.CATEGORY_VIDEO
            }.getOrDefault(false)
            packageQueries.remove(packageName)
            withWindowLock { requestTraversal() }
        }
        return false
    }

    private fun refreshMediaPackages() {
        val currentContext = context ?: return
        if (withWindowLock { host?.active == true } != true) return
        // Query app sessions outside the WM global lock; traversal reads only snapshots.
        mediaPlayback = runCatching {
            currentContext.getSystemService(MediaSessionManager::class.java)?.getActiveSessions(null)
                .orEmpty().mapNotNull { controller ->
                    val state = runCatching { controller.playbackState }.getOrNull() ?: return@mapNotNull null
                    val metadata = runCatching { controller.metadata }.getOrNull()
                    val duration = (metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)
                    Playback(controller, controller.packageName,
                        runCatching { ControlVideoReflect.call(controller.sessionToken, "getUid") as? Int }.getOrNull(),
                        state, duration, metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf { it.isNotBlank() })
                }
        }.getOrDefault(emptyList())
        mediaPackages = mediaPlayback.filter { it.playing || it.state.state == PlaybackState.STATE_PAUSED }
            .map { it.packageName }.toSet()
        probeNativePlayback()
        withWindowLock { if (host?.active == true) requestTraversal() }
        handler.removeCallbacks(mediaRefresh)
        handler.postDelayed(mediaRefresh, 1_000L)
    }

    private fun externalPackage(packageName: String): Boolean = packageName.isNotBlank() &&
        packageName !in setOf("android", "com.android.systemui", "io.github.lujinxin.nextep")

    private fun sameSurface(first: SurfaceControl, second: SurfaceControl?): Boolean =
        second != null && first.isValid && second.isValid &&
            (first === second || ControlVideoReflect.call(first, "isSameSurface", second) == true)

    private fun inputHandles(windows: List<Any>): List<Any> = windows.mapNotNull { window ->
        ControlVideoReflect.field(window, "mInputWindowHandle")?.let { ControlVideoReflect.field(it, "mHandle") }
    }

    private fun updateFloatingPipInputOrder(source: Source?, display: Any, transaction: SurfaceControl.Transaction) {
        val task = source?.container
        val workspaceAnchor = task?.let { ControlVideoReflect.field(it, "mLastRelativeToLayer") as? SurfaceControl }
            ?.takeIf { it.isValid }
        // The PiP task can itself be relative to a workspace panel. Put the consumer
        // alongside it under the same anchor instead of chaining relative layers.
        val anchor = workspaceAnchor ?: task?.let { ControlVideoReflect.call(it, "getSurfaceControl") as? SurfaceControl }
        val nativeLayer = if (workspaceAnchor != null) {
            ControlVideoReflect.field(checkNotNull(task), "mLastLayer") as? Int
        } else {
            task?.let { ControlVideoReflect.call(it, "getChildCount") as? Int }
        }
        ControlAreaVideoInputHook.setFloatingPipInputAnchor(anchor, 1 + (nativeLayer ?: 0), display, transaction)
    }


    private fun forgetExitedPip() {
        if (!observedSourceWasPip) return
        val previous = observedSource?.get()
        if (previous != null && isPip(previous) &&
            ControlVideoReflect.call(previous, "isAttached") != false) return
        // A long-lived task can reuse its ActivityRecord and surface every time it enters PiP.
        // Keep the manual-return choice through a visibility gap only while it is still pinned.
        observedSource = null
        observedSourceSurface = null
        observedSourceWasPip = false
    }

    /** An ephemeral window lifetime, independent of the application and host session. */
    private fun sourceId(source: Source?): Long {
        if (source == null) {
            // Locking or a native transition can temporarily hide the same window.
            // Preserve its weak identity so a manual return survives that visibility gap.
            return 0L
        }
        if (observedSource?.get() !== source.container ||
            observedSourceSurface?.get()?.let { sameSurface(it, source.surface) } != true) {
            observedSource = WeakReference(source.container)
            observedSourceSurface = WeakReference(source.surface)
            observedSourceWasPip = source.pip
            sourceGeneration += 1L
        }
        return sourceGeneration
    }

    private fun publish(state: ControlAreaVideoState) {
        if (lastState == state) return
        val diagnostic = "Video state source=${state.sourceId} package=${state.packageName ?: "none"} pip=${state.sourceIsPip} " +
            "available=${state.available} playbackControl=${state.canControlPlayback} " +
            "embedded=${state.embedded} nativeInput=${state.nativeInput} nativeSeek=${state.nativeSeekAvailable} " +
            "mediaProgress=${state.hasPlaybackProgress} mediaSeek=${state.canSeek} reason=${state.reason ?: "none"}"
        if (lastDiagnosticState != diagnostic) {
            lastDiagnosticState = diagnostic
            NeXtepLog.info("control_video", diagnostic)
        }
        lastState = state
        val currentContext = context ?: return
        handler.post { runCatching { ControlAreaVideoContract.publishState(currentContext, state) }
            .onFailure { NeXtepLog.warn("control_video", "Video state unavailable", it) } }
    }

    private fun requestTraversal() {
        service?.let { wm ->
            ControlVideoReflect.field(wm, "mWindowPlacerLocked")?.let {
                ControlVideoReflect.call(it, "requestTraversal")
            }
        }
    }

    private fun requestInputUpdate() {
        val display = service?.let { ControlVideoReflect.field(it, "mRoot") }
            ?.let { ControlVideoReflect.call(it, "getDefaultDisplay") } ?: return
        val monitor = ControlVideoReflect.call(display, "getInputMonitor") ?: return
        ControlVideoReflect.call(monitor, "setUpdateInputWindowsNeededLw")
        ControlVideoReflect.call(monitor, "updateInputWindowsLw", true)
    }

    private fun <T> withWindowLock(block: () -> T): T? {
        val lock = service?.let { ControlVideoReflect.field(it, "mGlobalLock") } ?: return null
        return synchronized(lock) { block() }
    }
}

/** Cached private API lookup, confined to this optional Beta feature. */
private object ControlVideoReflect {
    private val fields = ConcurrentHashMap<String, Field>()
    private val methods = ConcurrentHashMap<String, Method>()
    private val missingFields = ConcurrentHashMap.newKeySet<String>()

    fun field(target: Any, name: String): Any? {
        val key = "${target.javaClass.name}#$name"
        if (key in missingFields) return null
        val member = fields[key] ?: generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()?.apply { isAccessible = true; fields[key] = this }
            ?: return null.also { missingFields.add(key) }
        return member.get(target)
    }

    fun call(target: Any, name: String, vararg arguments: Any?): Any? =
        runCatching { callRequired(target, name, *arguments) }.getOrNull()

    fun callRequired(target: Any, name: String, vararg arguments: Any?): Any? {
        val key = "${target.javaClass.name}#$name/${arguments.joinToString { it?.javaClass?.name ?: "null" }}"
        val member = methods[key] ?: generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }.firstOrNull { method ->
                method.name == name && method.parameterCount == arguments.size &&
                    method.parameterTypes.indices.all { index ->
                        val type = method.parameterTypes[index]
                        val value = arguments[index]
                        when {
                            value == null -> !type.isPrimitive
                            type.isInstance(value) -> true
                            type == Int::class.javaPrimitiveType -> value is Int
                            type == Float::class.javaPrimitiveType -> value is Float
                            type == Boolean::class.javaPrimitiveType -> value is Boolean
                            else -> false
                        }
                    }
            }?.apply { isAccessible = true; methods[key] = this }
            ?: error("Missing ${target.javaClass.name}#$name")
        return member.invoke(target, *arguments)
    }
}
