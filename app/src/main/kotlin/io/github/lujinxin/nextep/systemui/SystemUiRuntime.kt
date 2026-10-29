package io.github.lujinxin.nextep.systemui

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.SurfaceControl
import io.github.lujinxin.nextep.apps.LauncherAppCatalogContract
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.config.WorkspaceConfigClient
import io.github.lujinxin.nextep.config.WorkspaceConfigContract
import io.github.lujinxin.nextep.config.WorkspaceTopConfig
import io.github.lujinxin.nextep.framework.TaskSurfaceCompat
import io.github.lujinxin.nextep.monitoring.WorkspaceEnergyContract
import io.github.lujinxin.nextep.monitoring.WorkspaceEnergyDiagnostics
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.task.MainTaskPresentationCoordinator
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import io.github.lujinxin.nextep.trigger.WorkspaceRecoveryPolicy
import io.github.lujinxin.nextep.workspace.SidebarSide
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import io.github.lujinxin.nextep.workspace.WorkspaceController
import io.github.lujinxin.nextep.workspace.WorkspaceStateBridge
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object SystemUiRuntime {
    private val initialized = AtomicBoolean(false)
    private lateinit var applicationContext: Context
    private lateinit var windowController: NeXtepWindowController
    private lateinit var workspaceController: WorkspaceController
    private lateinit var fourFingerGestures: FourFingerGestureController
    private val lifecycleHandler = Handler(Looper.getMainLooper())
    private val configExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NeXtep-WorkspaceConfig").apply { isDaemon = true }
    }
    @Volatile private var workspaceConfig = WorkspaceTopConfig()
    @Volatile private var workspaceConfigReady = false
    private val configRetry = Runnable { refreshWorkspaceConfig() }
    private val taskRefreshQueued = AtomicBoolean(false)
    private val taskRefresh = Runnable {
        taskRefreshQueued.set(false)
        if (::windowController.isInitialized) windowController.onTasksChanged()
    }

    fun onTasksChanged() {
        if (taskRefreshQueued.compareAndSet(false, true)) lifecycleHandler.post(taskRefresh)
    }
    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WorkspaceConfigContract.ACTION_CHANGED) refreshWorkspaceConfig()
        }
    }

    private fun refreshWorkspaceConfig() {
        lifecycleHandler.removeCallbacks(configRetry)
        configExecutor.execute {
            // Query the module even before the workspace windows exist.
            val queriedConfig = WorkspaceConfigClient.queryOrNull(applicationContext)
            if (queriedConfig == null) {
                // Credential-protected module settings are unavailable before first unlock.
                lifecycleHandler.postDelayed(configRetry, 2_000L)
                return@execute
            }
            workspaceConfig = queriedConfig
            AppLanguage.updateHostLanguage(queriedConfig.languageTag)
            workspaceConfigReady = true
            lifecycleHandler.post {
                if (::windowController.isInitialized) {
                    windowController.setSlotBackgroundSwipeEnabled(workspaceConfig.slotBackgroundSwipeEnabled)
                    windowController.setNavigationButtonsEnabled(workspaceConfig.navigationButtonsEnabled)
                }
                if (::fourFingerGestures.isInitialized) fourFingerGestures.updateConfig(workspaceConfig)
            }
            NeXtepLog.info(
                "workspace_config",
                "Applied statusBarGesture=${workspaceConfig.statusBarGestureEnabled} " +
                    "slotBackgroundSwipe=${workspaceConfig.slotBackgroundSwipeEnabled} " +
                    "autoMinimizeMain=${workspaceConfig.autoMinimizeMainOnTopAppSwitch} " +
                    "navigationButtons=${workspaceConfig.navigationButtonsEnabled}",
            )
        }
    }

    fun isStatusBarGestureEnabled(): Boolean = workspaceConfigReady && workspaceConfig.statusBarGestureEnabled

    fun shouldAutoMinimizeMainOnTopAppSwitch(): Boolean = workspaceConfig.autoMinimizeMainOnTopAppSwitch

    fun swapAnimationDurationMs(): Int = workspaceConfig.swapAnimationDurationMs
    private val lockStateObserver = object : Runnable {
        override fun run() {
            if (!::workspaceController.isInitialized || !workspaceController.isActive()) return
            val screenReady = isScreenReady()
            if (!screenReady || isKeyguardLocked(applicationContext)) {
                if (!workspaceController.isSuspended()) {
                    workspaceController.suspendForLock()
                    publishWorkspaceSurfaceState(false)
                }
                // A keyguard-going-away sync can include retained virtual-display
                // activities. Keeping their outputs OFF until keyguard is gone
                // makes that sync wait for its five-second draw timeout.
                // Panels and input remain suspended; only hidden buffers warm up.
                windowController.updateSuspendedOutputs(screenReady)
            } else if (workspaceController.isSuspended()) {
                lifecycleHandler.removeCallbacks(resumeAfterUnlock)
                resumeAfterUnlock.run()
            }
            updateLockStateObservation()
        }
    }

    private fun updateLockStateObservation() {
        // State transitions can occur inside an observation callback. Remove any
        // queued copy before scheduling, so there is only one fallback chain.
        lifecycleHandler.removeCallbacks(lockStateObserver)
        if (::workspaceController.isInitialized && workspaceController.isActive()) {
            lifecycleHandler.postDelayed(lockStateObserver, 500L)
        } else {
            lifecycleHandler.removeCallbacks(resumeAfterUnlock)
        }
    }
    private val resumeAfterUnlock = object : Runnable {
        override fun run() {
            lifecycleHandler.removeCallbacks(this)
            if (!::workspaceController.isInitialized || !workspaceController.isSuspended()) return
            val locked = isKeyguardLocked(applicationContext)
            val screenReady = isScreenReady()
            windowController.updateSuspendedOutputs(screenReady)
            if (locked || !screenReady) {
                // USER_PRESENT can precede the physical display becoming ready.
                // Keep this attempt alive instead of waiting for another broadcast.
                lifecycleHandler.postDelayed(this, if (locked) 500L else UNLOCK_DISPLAY_RETRY_MS)
                return
            }
            val resumed = workspaceController.resumeAfterLock()
            publishWorkspaceSurfaceState(resumed)
            if (!resumed) WorkspaceStateBridge.requestInactiveFromSystemUi(applicationContext)
        }
    }
    private val keyguardStateListener = KeyguardManager.KeyguardLockedStateListener {
        // Re-read current state: a queued callback can belong to an earlier wake.
        lockStateObserver.run()
    }
    private val displayStateListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) lockStateObserver.run()
        }
    }

    @SuppressLint("MissingPermission")
    private fun observeLockAndDisplayState() {
        // These callbacks execute in SystemUI. Keep the broadcast/polling fallback
        // when an OEM does not grant its host the keyguard subscription permission.
        runCatching {
            checkNotNull(applicationContext.getSystemService(KeyguardManager::class.java))
                .addKeyguardLockedStateListener(applicationContext.mainExecutor, keyguardStateListener)
        }.onFailure {
            NeXtepLog.warn("workspace_unlock", "Keyguard callback unavailable; retaining state polling", it)
        }
        runCatching {
            checkNotNull(applicationContext.getSystemService(DisplayManager::class.java))
                .registerDisplayListener(displayStateListener, lifecycleHandler)
        }.onFailure {
            NeXtepLog.warn("workspace_unlock", "Display callback unavailable; retaining state polling", it)
        }
    }

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        try {
            applicationContext = context.applicationContext ?: context
            LauncherAppCatalogContract.register(applicationContext)
            TaskSurfaceCompat.initializeTaskAccess(applicationContext)
            WorkspaceDisplayTransitionHook.initialize(applicationContext)
            val mainTaskPresenter = MainTaskPresentationCoordinator(applicationContext)
            windowController = NeXtepWindowController(
                context = applicationContext,
                mainTaskPresenter = mainTaskPresenter,
                onSidebarSideRequested = ::setSidebarSideFromTopBar,
                onSettingsRequested = ::openSettingsFromTopBar,
                onExitRequested = ::closeWorkspaceFromGesture,
                onViewportChanged = ::reconfigureForViewport,
            )
            workspaceController = WorkspaceController(
                applicationContext = applicationContext,
                windowController = windowController,
                mainTaskPresenter = mainTaskPresenter,
                onStateChanged = ::updateLockStateObservation,
            )
            fourFingerGestures = FourFingerGestureController(
                applicationContext,
                isWorkspaceActive = ::isWorkspaceActive,
                visibleSlotTaskIds = windowController::visibleSlotTaskIds,
                canChangeWorkspace = { !workspaceController.isSuspended() },
                requestWorkspace = ::setWorkspaceFromFourFingerGesture,
            )

            val filter = IntentFilter().apply {
                addAction(TriggerBroadcastContract.ACTION_TOGGLE_WORKSPACE)
                addAction(TriggerBroadcastContract.ACTION_SET_WORKSPACE)
                addAction(TriggerBroadcastContract.ACTION_QUERY_WORKSPACE)
                addAction(TriggerBroadcastContract.ACTION_LAUNCH_SLOT_TEST)
                addAction(TriggerBroadcastContract.ACTION_FOUR_FINGER_WORKSPACE)
            }
            applicationContext.registerReceiver(
                controlReceiver,
                filter,
                TriggerBroadcastContract.CONTROL_PERMISSION,
                Handler(Looper.getMainLooper()),
                Context.RECEIVER_EXPORTED,
            )
            applicationContext.registerReceiver(
                launcherRequestReceiver,
                IntentFilter(TriggerBroadcastContract.ACTION_LAUNCHER_APP_REQUEST),
                Context.RECEIVER_EXPORTED,
            )
            applicationContext.registerReceiver(
                configReceiver,
                IntentFilter(WorkspaceConfigContract.ACTION_CHANGED),
                Context.RECEIVER_EXPORTED,
            )
            refreshWorkspaceConfig()
            val lifecycleFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            }
            runCatching {
                applicationContext.registerReceiver(
                    lifecycleReceiver,
                    lifecycleFilter,
                    Context.RECEIVER_NOT_EXPORTED,
                )
            }.onFailure { error ->
                NeXtepLog.warn("systemui_runtime", "Lifecycle receiver unavailable", error)
            }
            observeLockAndDisplayState()
            runCatching {
                WorkspaceEnergyContract.register(applicationContext, lifecycleHandler) {
                    windowController.energySnapshot().apply {
                        putInt("version", 1)
                        putInt("pid", android.os.Process.myPid())
                        putLong("elapsed_ms", android.os.SystemClock.elapsedRealtime())
                        putLong("cpu_ms", android.os.Process.getElapsedCpuTime())
                        putBoolean("active", workspaceController.isActive())
                        putBoolean("suspended", workspaceController.isSuspended())
                        putLong("task_queries", WorkspaceEnergyDiagnostics.taskQueries.get())
                        putLong("preview_captures", WorkspaceEnergyDiagnostics.previewCaptures.get())
                    }
                }
            }.onFailure { NeXtepLog.warn("energy_monitor", "Runtime snapshot unavailable", it) }
            Handler(Looper.getMainLooper()).post {
                SystemServerWorkspaceBridge.publish(applicationContext, false)
                WorkspaceStateBridge.requestInactiveFromSystemUi(applicationContext)
                // OWN_DISPLAY_GROUP virtual screens can keep the device globally interactive
                // after the physical screen locks, so SCREEN_OFF alone is insufficient.
                updateLockStateObservation()
            }
            NeXtepLog.info("systemui_runtime", "Initialized control receiver")
        } catch (error: Throwable) {
            initialized.set(false)
            NeXtepLog.error("systemui_runtime", "Initialization failed and remains retryable", error)
        }
    }

    fun isWorkspaceActive(): Boolean =
        ::workspaceController.isInitialized && workspaceController.isActive()

    fun prepareDisplayRotation(rotation: Int, generation: Long) {
        // WM can deliver a resize before SystemUI's configuration callback is
        // dequeued. Its first drawing pass must already know the target angle.
        if (::windowController.isInitialized) windowController.stagePanelRotation(rotation)
        lifecycleHandler.post {
            if (!::workspaceController.isInitialized || !::windowController.isInitialized ||
                !workspaceController.isActive() || workspaceController.isSuspended() ||
                !TaskSurfaceCompat.isDisplayRotationPending(generation)) return@post
            windowController.projectDisplayRotation(rotation)
            workspaceController.reconfigure(refreshViewport = false, deferMainPresentation = true)
            windowController.whenRotationFrameReady { host ->
                if (!workspaceController.isActive() || workspaceController.isSuspended()) {
                    TaskSurfaceCompat.completeDisplayRotation(generation)
                    return@whenRotationFrameReady
                }
                val transaction = SurfaceControl.Transaction()
                runCatching {
                    if (!TaskSurfaceCompat.finishDisplayRotation(generation, transaction)) {
                        TaskSurfaceCompat.completeDisplayRotation(generation)
                        transaction.close()
                        return@whenRotationFrameReady
                    }
                    transaction.addTransactionCommittedListener({ command -> lifecycleHandler.post(command) }) {
                        TaskSurfaceCompat.completeDisplayRotation(generation)
                        transaction.close()
                        NeXtepLog.info("workspace_display_transition", "Fitted main task with ready panel frame rotation=$rotation")
                    }
                    if (host.rootSurfaceControl?.applyTransactionOnDraw(transaction) != true) transaction.apply()
                    host.invalidate()
                }.onFailure {
                    TaskSurfaceCompat.completeDisplayRotation(generation)
                    transaction.close()
                    NeXtepLog.warn("workspace_display_transition", "Panel rotation handoff failed; presentation will retry", it)
                }
            }
        }
    }

    fun toggleWorkspaceFromGesture() {
        if (!isStatusBarGestureEnabled() || !::workspaceController.isInitialized) return
        val target = !workspaceController.isActive()
        if (target && isKeyguardLocked(applicationContext)) return
        if (workspaceController.setActive(target)) {
            publishWorkspaceSurfaceState(target)
            if (!target) WorkspaceStateBridge.requestInactiveFromSystemUi(applicationContext)
        }
    }

    fun closeWorkspaceFromGesture() {
        if (::workspaceController.isInitialized) {
            workspaceController.setActive(false)
        }
        if (::applicationContext.isInitialized) {
            TriggerBroadcastContract.systemUiSetIntent(applicationContext, false)?.let { intent ->
                applicationContext.sendBroadcast(intent)
            }
            applicationContext.sendBroadcast(
                TriggerBroadcastContract.assistantScreenSetIntent(applicationContext, false),
            )
            WorkspaceStateBridge.requestInactiveFromSystemUi(applicationContext)
        }
    }

    private fun setWorkspaceFromFourFingerGesture(active: Boolean) {
        if (!workspaceConfigReady || !workspaceConfig.fourFingerGestureEnabled ||
            !::workspaceController.isInitialized || !isScreenReady() || isKeyguardLocked(applicationContext) ||
            workspaceController.isSuspended() || workspaceController.isActive() == active
        ) return
        if (workspaceController.setActive(active)) {
            publishWorkspaceSurfaceState(active)
            if (!active) WorkspaceStateBridge.requestInactiveFromSystemUi(applicationContext)
        }
    }

    private fun publishWorkspaceSurfaceState(active: Boolean) {
        TriggerBroadcastContract.systemUiSetIntent(applicationContext, active)?.let {
            applicationContext.sendBroadcast(it)
        }
        applicationContext.sendBroadcast(
            TriggerBroadcastContract.assistantScreenSetIntent(applicationContext, active),
        )
    }

    private fun setSidebarSideFromTopBar(side: SidebarSide) {
        if (::workspaceController.isInitialized && !workspaceController.setSidebarSide(side)) {
            NeXtepLog.warn("sidebar_side", "Unable to switch side=$side")
        }
    }

    private fun reconfigureForViewport() {
        if (!::workspaceController.isInitialized || !workspaceController.isActive() ||
            workspaceController.isSuspended()) return
        // A manual navigation-mode change has already updated the cached viewport.
        if (!workspaceController.reconfigure(refreshViewport = false)) {
            publishWorkspaceSurfaceState(false)
            WorkspaceStateBridge.requestInactiveFromSystemUi(applicationContext)
        }
    }

    private fun openSettingsFromTopBar() {
        val intent = applicationContext.packageManager
            .getLaunchIntentForPackage(TriggerBroadcastContract.MODULE_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent == null) {
            NeXtepLog.warn("top_settings", "Module launch intent unavailable")
            return
        }
        if (::workspaceController.isInitialized && workspaceController.isActive()) {
            // Use the same task/display coordination as a launcher click, including
            // an existing NeXtep task in a slot. Opening settings must retain workspace.
            if (!workspaceController.openFromLauncher(intent)) {
                NeXtepLog.warn("top_settings", "Unable to open settings in workspace")
            }
            return
        }
        runCatching { applicationContext.startActivity(intent) }
            .onFailure { NeXtepLog.warn("top_settings", "Unable to open settings", it) }
    }

    private val lifecycleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) refreshWorkspaceConfig()
            if (intent.action == Intent.ACTION_SCREEN_OFF) TaskSurfaceCompat.finishWorkspaceExitAnimations()
            if (!::workspaceController.isInitialized || !workspaceController.isActive()) return
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    lifecycleHandler.removeCallbacks(resumeAfterUnlock)
                    workspaceController.suspendForLock()
                    windowController.updateSuspendedOutputs(screenAwake = false)
                    publishWorkspaceSurfaceState(false)
                    return
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    lifecycleHandler.removeCallbacks(resumeAfterUnlock)
                    lifecycleHandler.post(resumeAfterUnlock)
                    return
                }
            }
            val reason = when (intent.action) {
                Intent.ACTION_CONFIGURATION_CHANGED -> {
                    if (workspaceController.isSuspended()) {
                        // Select the recorded rotation/density profile at the actual
                        // configuration event, never during a task exchange or every wake.
                        windowController.currentGeometry(refreshViewport = true)
                        return
                    }
                    if (workspaceController.reconfigure()) {
                        NeXtepLog.info(
                            "systemui_runtime",
                            "Workspace retained across configuration change",
                        )
                        return
                    }
                    "configuration reconfigure failed"
                }
                else -> return
            }
            workspaceController.setActive(false)
            WorkspaceStateBridge.requestInactiveFromSystemUi(context)
            NeXtepLog.info("systemui_runtime", "Workspace closed fail-open: $reason")
        }
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isOrderedBroadcast || !::workspaceController.isInitialized) return
            val shouldCollapseFirst = intent.getStringExtra(
                TriggerBroadcastContract.EXTRA_SOURCE,
            ) == io.github.lujinxin.nextep.trigger.TriggerSource.QUICK_SETTINGS_TILE.name &&
                requestedTarget(intent) == true
            if (shouldCollapseFirst) {
                val pendingResult = goAsync()
                SystemUiPanelCollapser.collapse(context)
                Handler(Looper.getMainLooper()).postDelayed({
                    val handled = handleControl(context, intent)
                    if (handled) {
                        pendingResult.setResultCode(TriggerBroadcastContract.RESULT_HANDLED)
                        pendingResult.setResultExtras(responseBundle(pendingResult.getResultExtras(true)))
                    }
                    pendingResult.finish()
                }, PANEL_COLLAPSE_DELAY_MS)
                return
            }
            val handled = handleControl(context, intent)
            if (!handled) return

            resultCode = TriggerBroadcastContract.RESULT_HANDLED
            setResultExtras(responseBundle(getResultExtras(true)))
        }
    }

    private fun requestedTarget(intent: Intent): Boolean? = when (intent.action) {
        TriggerBroadcastContract.ACTION_TOGGLE_WORKSPACE -> !workspaceController.isActive()
        TriggerBroadcastContract.ACTION_SET_WORKSPACE -> intent.getBooleanExtra(
            TriggerBroadcastContract.EXTRA_REQUESTED_ACTIVE,
            false,
        )
        else -> null
    }

    private fun handleControl(context: Context, intent: Intent): Boolean = when (intent.action) {
        TriggerBroadcastContract.ACTION_TOGGLE_WORKSPACE,
        TriggerBroadcastContract.ACTION_SET_WORKSPACE -> {
            val target = requestedTarget(intent) ?: false
            if (!WorkspaceRecoveryPolicy.shouldApply(target,
                    intent.getBooleanExtra(TriggerBroadcastContract.EXTRA_ONLY_IF_INACTIVE, false),
                    workspaceController.isActive())) true
            else if (target && isKeyguardLocked(context)) false
            else workspaceController.setActive(target).also { handled ->
                // The tile/coordinated path must also restore the separate assistant panel.
                if (handled) publishWorkspaceSurfaceState(target)
            }
        }
        TriggerBroadcastContract.ACTION_QUERY_WORKSPACE -> true
        TriggerBroadcastContract.ACTION_FOUR_FINGER_WORKSPACE -> fourFingerGestures.handleRequest(intent)
        TriggerBroadcastContract.ACTION_LAUNCH_SLOT_TEST -> {
            val active = workspaceController.isActive() || workspaceController.setActive(true)
            active && workspaceController.launchInSlot(
                Intent(Settings.ACTION_SETTINGS).setPackage("com.android.settings"),
            )
        }
        else -> false
    }

    private fun responseBundle(existing: Bundle?): Bundle = (existing ?: Bundle()).apply {
        putBoolean(
            TriggerBroadcastContract.EXTRA_ACTIVE,
            workspaceController.isActive(),
        )
        putString(
            TriggerBroadcastContract.EXTRA_HOME_PACKAGE,
            TriggerBroadcastContract.SYSTEM_UI_PACKAGE,
        )
        putInt(
            TriggerBroadcastContract.EXTRA_RUNTIME_PROTOCOL_VERSION,
            TriggerBroadcastContract.RUNTIME_PROTOCOL_VERSION,
        )
        putString(
            TriggerBroadcastContract.EXTRA_SLOT_STATES,
            workspaceController.slotStatesDescription(),
        )
    }

    private val launcherRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!::workspaceController.isInitialized || !workspaceController.isActive()) return
            val homePackage = TriggerBroadcastContract.resolveHomePackage(context) ?: return
            val launcherIdentity = intent.getParcelableExtra(
                TriggerBroadcastContract.EXTRA_LAUNCHER_IDENTITY,
                PendingIntent::class.java,
            )
            val verifiedSender = sentFromPackage ?: launcherIdentity?.creatorPackage
            if (verifiedSender != homePackage) {
                NeXtepLog.warn(
                    "launcher_redirect",
                    "Rejected sender=$sentFromPackage token=${launcherIdentity?.creatorPackage} " +
                        "uid=$sentFromUid expected=$homePackage",
                )
                return
            }
            val launchIntent = intent.getParcelableExtra(
                TriggerBroadcastContract.EXTRA_LAUNCH_INTENT,
                Intent::class.java,
            ) ?: return
            if (!workspaceController.openFromLauncher(launchIntent)) {
                NeXtepLog.warn(
                    "launcher_redirect",
                    "SystemUI could not handle component=${launchIntent.component}",
                )
            }
        }
    }

    private fun isKeyguardLocked(context: Context): Boolean = try {
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    } catch (error: Throwable) {
        NeXtepLog.warn("systemui_runtime", "Keyguard query failed; activation denied", error)
        true
    }

    private fun isScreenReady(): Boolean =
        applicationContext.getSystemService(PowerManager::class.java)?.isInteractive == true &&
            applicationContext.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON

    private const val UNLOCK_DISPLAY_RETRY_MS = 100L
    private const val PANEL_COLLAPSE_DELAY_MS = 380L
}
