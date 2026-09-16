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

    fun isActive(): Boolean = state is WorkspaceState.Active

    fun slotStatesDescription(): String = windowController.slotStatesDescription()

    fun launchInSlot(intent: Intent): Boolean {
        if (!isActive()) return false
        return windowController.launchInSlot(intent).isSuccess
    }

    fun openFromLauncher(intent: Intent): Boolean {
        if (!isActive()) return false
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
            SystemUiRootTransformController.setSidebarSide(side)
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
            SystemUiRootTransformController.setSidebarSide(previous.sidebarSide)
            mainTaskPresenter.reconcileForeground(previous)
            SystemServerWorkspaceBridge.publish(applicationContext, true, previous)
            SystemDialogLayoutController.setGeometry(previous)
            NeXtepLog.error("workspace", "Sidebar move rolled back side=$side", error)
            false
        }
    }

    fun reconfigure(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Workspace configuration changes must run on the main thread"
        }
        if (!isActive()) return true
        return runCatching {
            val geometry = windowController.reconfigure().getOrThrow()
            SystemUiRootTransformController.setSidebarSide(geometry.sidebarSide)
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
            true
        }.onFailure { error ->
            NeXtepLog.error("workspace_configuration", "Reconfigure failed open", error)
            exit()
        }.getOrDefault(false)
    }

    private fun enter(): Boolean {
        if (state is WorkspaceState.Active) return true
        if (state is WorkspaceState.Entering || state is WorkspaceState.Exiting) return false
        state = WorkspaceState.Entering
        return try {
            val requestedGeometry = windowController.currentGeometry()
            SystemUiRootTransformController.setSidebarSide(requestedGeometry.sidebarSide)
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
        val activeGeometry = (state as? WorkspaceState.Active)?.geometry
        state = WorkspaceState.Exiting
        return try {
            SystemServerWorkspaceBridge.publish(applicationContext, false)
            SystemDialogLayoutController.setGeometry(null)
            SystemUiRootTransformController.setActive(false)
            val restoreResult = mainTaskPresenter.restoreForeground(activeGeometry)
            windowController.hide()
            restoreResult.getOrThrow()
            state = WorkspaceState.Disabled
            NeXtepLog.info("workspace", "Exited")
            true
        } catch (error: Throwable) {
            state = WorkspaceState.Faulted(error.message ?: error.javaClass.simpleName)
            NeXtepLog.error("workspace", "Exit cleanup failed", error)
            false
        }
    }
}
