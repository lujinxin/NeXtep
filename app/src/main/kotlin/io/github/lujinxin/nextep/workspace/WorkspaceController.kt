package io.github.lujinxin.nextep.workspace

import android.content.Context
import android.content.Intent
import android.os.Looper
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.systemui.NeXtepWindowController
import io.github.lujinxin.nextep.systemui.SystemUiRootTransformController
import io.github.lujinxin.nextep.systemui.SystemDialogLayoutController
import io.github.lujinxin.nextep.task.MainTaskPresentationCoordinator
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

class WorkspaceController(
    private val applicationContext: Context,
    private val windowController: NeXtepWindowController,
    private val mainTaskPresenter: MainTaskPresentationCoordinator,
) {
    var state: WorkspaceState = WorkspaceState.Disabled
        private set

    fun setActive(active: Boolean): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Workspace transitions must run on the main thread"
        }
        return if (active) enter() else exit()
    }

    fun isActive(): Boolean = state is WorkspaceState.Active || state is WorkspaceState.Suspended

    fun isSuspended(): Boolean = state is WorkspaceState.Suspended

    fun suspendForLock() {
        val current = state as? WorkspaceState.Active ?: return
        state = WorkspaceState.Suspended(current.geometry)
        windowController.suspendForLock()
        SystemServerWorkspaceBridge.publish(applicationContext, false)
        SystemUiRootTransformController.setActive(false)
        SystemDialogLayoutController.setGeometry(null)
        mainTaskPresenter.restoreForeground().onFailure {
            NeXtepLog.warn("workspace", "Unable to restore main task during lock", it)
        }
        NeXtepLog.info("workspace", "Suspended for lock; slot tasks retained")
    }

    fun resumeAfterLock(): Boolean {
        if (!isSuspended()) return true
        return runCatching {
            val geometry = windowController.currentGeometry(refreshViewport = true)
            mainTaskPresenter.presentForeground(geometry).getOrThrow()
            SystemUiRootTransformController.setGeometry(geometry)
            SystemUiRootTransformController.setActive(true)
            windowController.resumeAfterLock()
            SystemServerWorkspaceBridge.publish(applicationContext, true, geometry)
            SystemDialogLayoutController.setGeometry(geometry)
            state = WorkspaceState.Active(geometry)
            NeXtepLog.info("workspace", "Resumed after unlock; slot tasks retained")
            true
        }.onFailure {
            NeXtepLog.warn("workspace", "Unlock resume will retry", it)
        }.getOrDefault(false)
    }

    fun slotStatesDescription(): String = windowController.slotStatesDescription()

    fun launchInSlot(intent: Intent): Boolean {
        if (state !is WorkspaceState.Active) return false
        return windowController.launchInSlot(intent).isSuccess
    }

    fun openFromLauncher(intent: Intent): Boolean {
        if (state !is WorkspaceState.Active) return false
        return windowController.openFromLauncher(intent).isSuccess
    }

    fun setSidebarSide(side: SidebarSide): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Sidebar transitions must run on the main thread"
        }
        val activeState = state as? WorkspaceState.Active ?: return false
        if (activeState.geometry.sidebarSide == side) return true
        val previous = activeState.geometry
        return try {
            val geometry = windowController.setSidebarSide(side).getOrThrow()
            SystemUiRootTransformController.setGeometry(geometry)
            mainTaskPresenter.reconcileForeground(geometry).getOrThrow()
            SystemServerWorkspaceBridge.publish(applicationContext, true, geometry)
            TriggerBroadcastContract.systemUiSetIntent(applicationContext, true)?.let {
                applicationContext.sendBroadcast(it)
            }
            applicationContext.sendBroadcast(
                TriggerBroadcastContract.assistantScreenSetIntent(applicationContext, true),
            )
            state = WorkspaceState.Active(geometry)
            SystemDialogLayoutController.setGeometry(geometry)
            NeXtepLog.info("workspace", "Sidebar moved side=$side geometry=$geometry")
            true
        } catch (error: Throwable) {
            windowController.setSidebarSide(previous.sidebarSide)
            SystemUiRootTransformController.setGeometry(previous)
            mainTaskPresenter.reconcileForeground(previous)
            SystemServerWorkspaceBridge.publish(applicationContext, true, previous)
            SystemDialogLayoutController.setGeometry(previous)
            NeXtepLog.error("workspace", "Sidebar move rolled back side=$side", error)
            false
        }
    }

    fun reconfigure(refreshViewport: Boolean = true): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Workspace configuration changes must run on the main thread"
        }
        val previous = state as? WorkspaceState.Active ?: return true
        return runCatching {
            // Task/display migrations can broadcast configuration changes without
            // changing our physical viewport. Keep attached panels in place.
            if (windowController.currentGeometry(refreshViewport) == previous.geometry) return@runCatching true
            val geometry = windowController.reconfigure().getOrThrow()
            SystemUiRootTransformController.setGeometry(geometry)
            mainTaskPresenter.reconcileForeground(geometry).onFailure {
                // A momentarily unavailable foreground task must not hide every panel.
                // SlotTaskCoordinator's foreground observer retries presentation.
                NeXtepLog.warn("workspace_configuration", "Main task settling during reconfigure; presentation will retry", it)
            }
            SystemServerWorkspaceBridge.publish(applicationContext, true, geometry)
            TriggerBroadcastContract.systemUiSetIntent(applicationContext, true)?.let {
                applicationContext.sendBroadcast(it)
            }
            applicationContext.sendBroadcast(
                TriggerBroadcastContract.assistantScreenSetIntent(applicationContext, true),
            )
            state = WorkspaceState.Active(geometry)
            SystemDialogLayoutController.setGeometry(geometry)
            true
        }.onFailure { error ->
            NeXtepLog.error("workspace_configuration", "Reconfigure failed open", error)
            exit()
        }.getOrDefault(false)
    }

    private fun enter(): Boolean {
        if (isActive()) return true
        if (state is WorkspaceState.Entering || state is WorkspaceState.Exiting) return false
        state = WorkspaceState.Entering
        return try {
            val requestedGeometry = windowController.currentGeometry(refreshViewport = true)
            SystemUiRootTransformController.setGeometry(requestedGeometry)
            mainTaskPresenter.presentForeground(requestedGeometry).getOrThrow()
            SystemUiRootTransformController.setActive(true)
            val geometry = windowController.show()
            SystemServerWorkspaceBridge.publish(applicationContext, true, geometry)
            applicationContext.sendBroadcast(
                TriggerBroadcastContract.assistantScreenSetIntent(applicationContext, true),
            )
            state = WorkspaceState.Active(geometry)
            SystemDialogLayoutController.setGeometry(geometry)
            NeXtepLog.info("workspace", "Entered geometry=$geometry")
            true
        } catch (error: Throwable) {
            SystemServerWorkspaceBridge.publish(applicationContext, false)
            SystemDialogLayoutController.setGeometry(null)
            applicationContext.sendBroadcast(
                TriggerBroadcastContract.assistantScreenSetIntent(applicationContext, false),
            )
            windowController.hide()
            SystemUiRootTransformController.setActive(false)
            mainTaskPresenter.restoreForeground()
            state = WorkspaceState.Faulted(error.message ?: error.javaClass.simpleName)
            NeXtepLog.error("workspace", "Enter failed open", error)
            false
        }
    }

    private fun exit(): Boolean {
        if (state is WorkspaceState.Disabled) return true
        if (state is WorkspaceState.Entering || state is WorkspaceState.Exiting) return false
        state = WorkspaceState.Exiting
        return try {
            SystemServerWorkspaceBridge.publish(applicationContext, false)
            SystemDialogLayoutController.setGeometry(null)
            SystemUiRootTransformController.setActive(false)
            val restoreResult = mainTaskPresenter.restoreForeground()
            windowController.hide()
            restoreResult.getOrThrow()
            state = WorkspaceState.Disabled
            NeXtepLog.info("workspace", "Exited")
            true
        } catch (error: Throwable) {
            state = WorkspaceState.Faulted(error.message ?: error.javaClass.simpleName)
            NeXtepLog.error("workspace", "Exit cleanup failed", error)
            // A failed task migration deliberately leaves the slot displays alive.
            // Restore the visible workspace rather than leaving half-disabled controls.
            runCatching {
                val geometry = windowController.resumeAfterLock()
                mainTaskPresenter.presentForeground(geometry).getOrThrow()
                SystemUiRootTransformController.setGeometry(geometry)
                SystemUiRootTransformController.setActive(true)
                SystemServerWorkspaceBridge.publish(applicationContext, true, geometry)
                SystemDialogLayoutController.setGeometry(geometry)
                state = WorkspaceState.Active(geometry)
            }.onFailure { error.addSuppressed(it) }
            false
        }
    }
}
