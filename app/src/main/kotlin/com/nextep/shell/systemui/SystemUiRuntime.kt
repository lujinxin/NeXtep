package com.nextep.shell.systemui

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.nextep.shell.logging.NeXtepLog
import com.nextep.shell.trigger.TriggerBroadcastContract
import com.nextep.shell.workspace.WorkspaceController
import com.nextep.shell.workspace.WorkspaceStateBridge
import com.nextep.shell.workspace.SystemServerWorkspaceBridge
import com.nextep.shell.task.MainTaskPresentationCoordinator
import com.nextep.shell.workspace.SidebarSide
import java.util.concurrent.atomic.AtomicBoolean

object SystemUiRuntime {
    private val initialized = AtomicBoolean(false)
    private lateinit var applicationContext: Context
    private lateinit var workspaceController: WorkspaceController

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        try {
            applicationContext = context.applicationContext ?: context
            val mainTaskPresenter = MainTaskPresentationCoordinator(applicationContext)
            workspaceController = WorkspaceController(
                applicationContext = applicationContext,
                windowController = NeXtepWindowController(
                    context = applicationContext,
                    mainTaskPresenter = mainTaskPresenter,
                    onSidebarSideRequested = ::setSidebarSideFromTopBar,
                    onSettingsRequested = ::openSettingsFromTopBar,
                    onExitRequested = ::closeWorkspaceFromGesture,
                ),
                mainTaskPresenter = mainTaskPresenter,
            )

            val filter = IntentFilter().apply {
                addAction(TriggerBroadcastContract.ACTION_TOGGLE_WORKSPACE)
                addAction(TriggerBroadcastContract.ACTION_SET_WORKSPACE)
                addAction(TriggerBroadcastContract.ACTION_QUERY_WORKSPACE)
                addAction(TriggerBroadcastContract.ACTION_LAUNCH_SLOT_TEST)
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
            val lifecycleFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
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
            Handler(Looper.getMainLooper()).post {
                SystemServerWorkspaceBridge.publish(applicationContext, false)
                WorkspaceStateBridge.requestInactiveFromSystemUi(applicationContext)
            }
            NeXtepLog.info("systemui_runtime", "Initialized control receiver")
        } catch (error: Throwable) {
            initialized.set(false)
            NeXtepLog.error("systemui_runtime", "Initialization failed and remains retryable", error)
        }
    }

    fun isWorkspaceActive(): Boolean =
        ::workspaceController.isInitialized && workspaceController.isActive()

    fun toggleWorkspaceFromGesture() {
        if (!::workspaceController.isInitialized) return
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

    private fun openSettingsFromTopBar() {
        closeWorkspaceFromGesture()
        val intent = applicationContext.packageManager
            .getLaunchIntentForPackage(TriggerBroadcastContract.MODULE_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent == null) {
            NeXtepLog.warn("top_settings", "Module launch intent unavailable")
            return
        }
        runCatching { applicationContext.startActivity(intent) }
            .onFailure { NeXtepLog.warn("top_settings", "Unable to open settings", it) }
    }

    private val lifecycleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!::workspaceController.isInitialized || !workspaceController.isActive()) return
            val reason = when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> "screen off"
                Intent.ACTION_CONFIGURATION_CHANGED -> {
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
            ) == com.nextep.shell.trigger.TriggerSource.QUICK_SETTINGS_TILE.name &&
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
            if (target && isKeyguardLocked(context)) false
            else workspaceController.setActive(target)
        }
        TriggerBroadcastContract.ACTION_QUERY_WORKSPACE -> true
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

    private const val PANEL_COLLAPSE_DELAY_MS = 380L
}
