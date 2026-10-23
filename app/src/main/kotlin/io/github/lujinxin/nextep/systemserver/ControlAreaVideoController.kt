package io.github.lujinxin.nextep.systemserver

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Point
import android.graphics.Rect
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.view.KeyEvent
import android.view.SurfaceControl
import android.view.WindowManager
import io.github.lujinxin.nextep.framework.ActivityTaskManagerCompat
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.ControlAreaVideoContract
import io.github.lujinxin.nextep.workspace.ControlAreaVideoState
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
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
        val death: IBinder.DeathRecipient,
    )

    private data class Source(
        val container: Any,
        val surface: SurfaceControl,
        val packageName: String,
        val pip: Boolean,
        val task: Boolean,
        val bounds: Rect,
        val windows: List<Any>,
        val secure: Boolean,
        val ownerUid: Int?,
    )

    private data class Embedded(val source: Source, val dock: SurfaceControl, val fallbackParent: SurfaceControl)

    private data class Playback(
        val controller: MediaController,
        val packageName: String,
        val ownerUid: Int?,
        val state: PlaybackState,
    ) {
        val playing: Boolean get() = state.state in setOf(PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING)
        val canControl: Boolean get() = state.actions and (PlaybackState.ACTION_PLAY_PAUSE or
            (if (playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY)) != 0L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val starting = AtomicBoolean(false)
    private val videoPackages = ConcurrentHashMap<String, Boolean>()
    private val packageQueries = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var mediaPackages: Set<String> = emptySet()
    @Volatile private var mediaPlayback: List<Playback> = emptyList()
    @Volatile private var inputHandles: List<Any> = emptyList()
    @Volatile private var pipEmbedded = false
    @Volatile private var systemUiUid: Int? = null
    private var service: Any? = null
    private var context: Context? = null
    private var host: Host? = null
    private val retiredSessions = LinkedHashSet<IBinder>()
    private var embedded: Embedded? = null
    private var captureRequested = false
    private var requestedSourceId = 0L
    private var rejectedSource: Source? = null
    private var rejectionReason: String? = null
    private var observedSource: WeakReference<Any>? = null
    private var observedSourceSurface: WeakReference<SurfaceControl>? = null
    private var sourceGeneration = 0L
    private var lastState: ControlAreaVideoState? = null
    private var lastDiagnosticState: String? = null
    private var failureReported = false
    private val mediaRefresh = Runnable { refreshMediaPackages() }

    private val releaseToken = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(ControlAreaVideoContract.RELEASE_DESCRIPTOR)
            val caller = Binder.getCallingUid()
            val owner = data.readStrongBinder()
            val expectedUid = systemUiUid
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
    fun ownsTask(task: Any): Boolean = embedded?.source?.let { it.task && it.container === task } == true

    fun onInputFailure(transaction: SurfaceControl.Transaction?) {
        withWindowLock {
            captureRequested = false
            if (transaction != null) restore(transaction) else restoreNow()
            requestTraversal()
        }
    }

    /** Called at the end of DisplayContent.prepareSurfaces under the existing WM global lock. */
    fun prepare(display: Any, transaction: SurfaceControl.Transaction) {
        if (ControlVideoReflect.call(display, "getDisplayId") != 0) return
        initialize(display)
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
                if (refreshed == null || refreshed.secure || inTransition(refreshed.container)) {
                    restore(transaction)
                    captureRequested = false
                } else {
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
                    inTransition(source.container) -> reason = "transitioning"
                    else -> {
                        val parent = nativeParent(source.container)?.takeIf { it.isValid }
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
                inputHandles = source.windows.mapNotNull { window ->
                    ControlVideoReflect.field(window, "mInputWindowHandle")?.let {
                        ControlVideoReflect.field(it, "mHandle")
                    }
                }
                pipEmbedded = source.pip
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
            val selected = embedded?.source ?: candidates.firstOrNull()
            val playback = selected?.let(::playbackFor)
            publish(ControlAreaVideoState(
                available = selected != null,
                embedded = embedded != null,
                packageName = selected?.packageName,
                sourceId = sourceId(selected),
                reason = reason ?: when {
                    selected?.secure == true -> "secure"
                    selected != null && inTransition(selected.container) -> "transitioning"
                    else -> null
                },
                session = currentHost.session,
                releaseToken = releaseToken,
                playing = playback?.playing ?: true,
                canControlPlayback = playback?.canControl == true,
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
                        ControlAreaVideoContract.COMMAND_CLOSE)) {
                    handlePlaybackCommand(session, intent.getLongExtra(ControlAreaVideoContract.EXTRA_SOURCE_ID, 0L), command)
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

    private fun handlePlaybackCommand(session: IBinder, requestedId: Long, command: String?) {
        val source = withWindowLock {
            embedded?.source?.takeIf { host?.session == session && host?.active == true &&
                requestedId > 0L && sourceId(it) == requestedId }
        } ?: return
        val playback = playbackFor(source)
        if (command == ControlAreaVideoContract.COMMAND_CLOSE) {
            // Some live sessions advertise STOP but ignore it. Pause before removing
            // their window so a no-op STOP cannot leave an invisible player audible.
            // App transport calls stay outside the WM global lock.
            playback?.let { setPlayback(it, playing = false) }
            val closed = withWindowLock {
                val current = embedded?.source ?: return@withWindowLock false
                if (host?.session != session || host?.active != true ||
                    current.container !== source.container || !sameSurface(current.surface, source.surface)) {
                    return@withWindowLock false
                }
                val taskId = if (source.task) checkNotNull(ControlVideoReflect.field(source.container, "mTaskId") as? Int) else null
                // Restore the native parent before removing the captured task/window.
                captureRequested = false
                restoreNow()
                try {
                    if (taskId != null) ActivityTaskManagerCompat.removeTask(taskId).getOrThrow()
                    else ControlVideoReflect.callRequired(source.container, "removeIfPossible")
                    publish(ControlAreaVideoState(session = session, releaseToken = releaseToken))
                    true
                } finally {
                    requestTraversal()
                }
            } == true
            if (closed) playback?.let { current ->
                runCatching {
                    when {
                        current.state.actions and PlaybackState.ACTION_STOP != 0L -> current.controller.transportControls.stop()
                        current.playing -> setPlayback(current, playing = false)
                    }
                }.onFailure { NeXtepLog.warn("control_video", "Closed video playback stop unavailable", it) }
            }
        } else {
            playback?.let { setPlayback(it, command == ControlAreaVideoContract.COMMAND_PLAY) }
        }
        // Read back the app's state after it has handled the transport command.
        handler.removeCallbacks(mediaRefresh)
        handler.postDelayed(mediaRefresh, 150L)
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

    private fun playbackFor(source: Source): Playback? = mediaPlayback.asSequence()
        .filter { it.packageName == source.packageName && (source.ownerUid == null || it.ownerUid == source.ownerUid) }
        .maxByOrNull { if (it.playing) 2 else if (it.state.state == PlaybackState.STATE_PAUSED) 1 else 0 }

    private fun updateHost(intent: Intent, session: IBinder, surface: SurfaceControl?) {
        val previous = host
        val sameOwner = previous?.session == session
        val active = intent.getBooleanExtra(ControlAreaVideoContract.EXTRA_ACTIVE, false) &&
            context?.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
        val width = intent.getIntExtra(ControlAreaVideoContract.EXTRA_WIDTH, 0).coerceIn(0, 16384)
        val height = intent.getIntExtra(ControlAreaVideoContract.EXTRA_HEIGHT, 0).coerceIn(0, 16384)
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
        host = Host(session, surface, width, height, active, death)
        previous?.surface?.takeIf { it !== surface }?.release()
        handler.removeCallbacks(mediaRefresh)
        if (active) handler.post(mediaRefresh)
        if (!active) publish(ControlAreaVideoState(session = session, releaseToken = releaseToken))
    }

    private fun applyDock(current: Embedded, target: Host, transaction: SurfaceControl.Transaction) {
        val source = current.source
        val position = (ControlVideoReflect.field(source.container, "mLastSurfacePosition") as? Point)
            ?.let(::Point) ?: Point().also { ControlVideoReflect.call(source.container, "getRelativePosition", it) }
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
        val scale = minOf(target.width.toFloat() / width, target.height.toFloat() / height)
        check(scale.isFinite() && scale > 0f)
        transaction.reparent(current.dock, checkNotNull(target.surface))
        transaction.reparent(source.surface, current.dock)
        transaction.setLayer(source.surface, 0)
        transaction.setLayer(current.dock, 1)
        ControlVideoReflect.callRequired(transaction, "setMatrix", current.dock, scale, 0f, 0f, scale)
        transaction.setPosition(current.dock,
            (target.width - width * scale) / 2f - position.x * scale,
            -position.y * scale)
        transaction.setCrop(current.dock, Rect(position.x, position.y, position.x + width, position.y + height))
        transaction.setVisibility(current.dock, true)
    }

    private fun restore(transaction: SurfaceControl.Transaction) {
        val current = embedded
        if (current != null && current.source.surface.isValid) {
            val source = current.source
            val parent = nativeParent(source.container)?.takeIf { it.isValid }
                ?: current.fallbackParent.takeIf { it.isValid }
            // Keep ownership until the native reparent is queued, so a failed request
            // can retry without losing the only reference to the docked source.
            transaction.reparent(source.surface, parent)
            runCatching {
                val relative = (ControlVideoReflect.call(source.container, "getLastRelativeLayer")
                    ?: ControlVideoReflect.field(source.container, "mLastRelativeToLayer")) as? SurfaceControl
                val layer = (ControlVideoReflect.call(source.container, "getLastLayer")
                    ?: ControlVideoReflect.field(source.container, "mLastLayer")) as? Int ?: 0
                if (relative?.isValid == true) {
                    ControlVideoReflect.callRequired(transaction, "setRelativeLayer", source.surface, relative, layer)
                } else transaction.setLayer(source.surface, layer)
            }.getOrElse {
                // Optional private layer metadata must never cancel the restoration.
                transaction.setLayer(source.surface, 0)
            }
        }
        embedded = null
        inputHandles = emptyList()
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
        val result = mutableListOf<Source>()
        ControlVideoReflect.callRequired(display, "forAllTasks", Consumer<Any> { task ->
            // OEM freeform surfaces can carry an independent scale matrix. Until that
            // transform has a reliable snapshot/restore path, accept stable system PiP
            // tasks and app overlays only; preserve all other native floating windows.
            if (!visible(task) || !isPip(task)) return@Consumer
            val root = ControlVideoReflect.call(task, "getRootTask")?.takeIf(::isPip) ?: task
            if (result.any { it.container === root }) return@Consumer
            val windows = windows(root)
            val owner = windows.firstNotNullOfOrNull { attrs(it)?.packageName?.takeIf(::externalPackage) }
                ?: return@Consumer
            val pip = ControlVideoReflect.call(root, "getWindowingMode") == 2
            source(root, owner, pip, true, windows)?.let(result::add)
        }, true)
        ControlVideoReflect.callRequired(display, "forAllWindows", Consumer<Any> { window ->
            val params = attrs(window) ?: return@Consumer
            if (params.type != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY || !visible(window)) return@Consumer
            val owner = params.packageName?.takeIf(::externalPackage) ?: return@Consumer
            if (!videoPackage(owner)) return@Consumer
            source(window, owner, false, false, listOf(window))?.let(result::add)
        }, true)
        return result.sortedBy { if (it.pip) 0 else 1 }
    }

    private fun source(container: Any, owner: String, pip: Boolean, task: Boolean, windows: List<Any>): Source? {
        val surface = ControlVideoReflect.call(container, "getSurfaceControl") as? SurfaceControl ?: return null
        if (!surface.isValid || windows.isEmpty()) return null
        val bounds = ((if (task) ControlVideoReflect.call(container, "getBounds")
            else ControlVideoReflect.call(container, "getFrame")) as? Rect)?.let(::Rect) ?: return null
        if (bounds.width() <= 1 || bounds.height() <= 1) return null
        val secure = windows.any { window ->
            (attrs(window)?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE != 0
        }
        val ownerUid = windows.firstNotNullOfOrNull { ControlVideoReflect.field(it, "mOwnerUid") as? Int }
        return Source(container, surface, owner, pip, task, bounds, windows, secure, ownerUid)
    }

    private fun windows(container: Any): List<Any> = mutableListOf<Any>().also { result ->
        ControlVideoReflect.callRequired(container, "forAllWindows", Consumer<Any> { window ->
            if (visible(window)) result.add(window)
        }, true)
    }

    private fun attrs(window: Any): WindowManager.LayoutParams? =
        ControlVideoReflect.field(window, "mAttrs") as? WindowManager.LayoutParams

    private fun visible(container: Any): Boolean = ControlVideoReflect.call(container, "isVisible") == true ||
        ControlVideoReflect.call(container, "isVisibleRequested") == true

    private fun isPip(task: Any): Boolean = ControlVideoReflect.call(task, "getWindowingMode") == 2

    private fun inTransition(container: Any): Boolean =
        ControlVideoReflect.call(container, "inTransition") == true ||
            (ControlVideoReflect.call(container, "getAnimationLeash") as? SurfaceControl)?.isValid == true

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
                    val state = controller.playbackState ?: return@mapNotNull null
                    Playback(controller, controller.packageName,
                        ControlVideoReflect.call(controller.sessionToken, "getUid") as? Int, state)
                }
        }.getOrDefault(emptyList())
        mediaPackages = mediaPlayback.filter { it.playing || it.state.state == PlaybackState.STATE_PAUSED }
            .map { it.packageName }.toSet()
        withWindowLock { if (host?.active == true) requestTraversal() }
        handler.removeCallbacks(mediaRefresh)
        handler.postDelayed(mediaRefresh, 1_000L)
    }

    private fun externalPackage(packageName: String): Boolean = packageName.isNotBlank() &&
        packageName !in setOf("android", "com.android.systemui", "io.github.lujinxin.nextep")

    private fun sameSurface(first: SurfaceControl, second: SurfaceControl?): Boolean =
        second != null && first.isValid && second.isValid &&
            (first === second || ControlVideoReflect.call(first, "isSameSurface", second) == true)

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
            sourceGeneration += 1L
        }
        return sourceGeneration
    }

    private fun publish(state: ControlAreaVideoState) {
        if (lastState == state) return
        val diagnostic = "Video state source=${state.sourceId} available=${state.available} " +
            "embedded=${state.embedded} reason=${state.reason ?: "none"}"
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
