package com.nextep.shell.task

import android.content.Context
import com.nextep.shell.logging.NeXtepLog
import com.nextep.shell.trigger.TriggerBroadcastContract
import com.nextep.shell.workspace.WorkspaceGeometry

class MainTaskPresentationCoordinator(context: Context) {
    private val applicationContext = context.applicationContext ?: context
    private val taskRepository = TaskRepository(applicationContext)
    private val surfacePresenter = SurfaceTaskPresenter()
    private var presentedTaskId: Int? = null
    private var activePresenter: MainTaskPresenter? = null

    fun presentForeground(geometry: WorkspaceGeometry): Result<Unit> {
        val task = taskRepository.foregroundTask()
            ?: return Result.failure(IllegalStateException("No foreground Display 0 task"))
        return presentTask(task.taskId, geometry)
    }

    fun presentTask(taskId: Int, geometry: WorkspaceGeometry): Result<Unit> {
        val task = taskRepository.findTask(taskId)
            ?: return Result.failure(IllegalStateException("Task $taskId is unavailable"))
        val homePackage = TriggerBroadcastContract.resolveHomePackage(applicationContext)
        if (task.component.packageName == homePackage) {
            NeXtepLog.info("main_task_presenter", "Foreground task is HOME; Launcher owns transform")
            return Result.success(Unit)
        }
        if (task.component.packageName == TriggerBroadcastContract.SYSTEM_UI_PACKAGE) {
            return Result.failure(
                IllegalStateException("Unsafe foreground task ${task.component.packageName}"),
            )
        }

        val surfaceResult = surfacePresenter.present(task.taskId, geometry)
        if (surfaceResult.isSuccess) {
            presentedTaskId = task.taskId
            activePresenter = surfacePresenter
        }
        return surfaceResult
    }

    fun reconcileForeground(geometry: WorkspaceGeometry): Result<Unit> = runCatching {
        val foreground = taskRepository.foregroundTask()
            ?: error("No foreground Display 0 task")
        val homePackage = TriggerBroadcastContract.resolveHomePackage(applicationContext)
        if (foreground.component.packageName == homePackage) {
            restoreForeground().getOrThrow()
            return@runCatching
        }
        if (foreground.component.packageName == TriggerBroadcastContract.SYSTEM_UI_PACKAGE) {
            return@runCatching
        }
        val current = presentedTaskId
        if (current != null && current != foreground.taskId) {
            restoreForeground().getOrThrow()
        }
        if (presentedTaskId == foreground.taskId) {
            activePresenter?.reapply(foreground.taskId, geometry)
                ?.getOrThrow()
                ?: error("Main presenter state is incomplete")
        } else {
            presentTask(foreground.taskId, geometry).getOrThrow()
        }
    }

    fun restoreForMove(taskId: Int): Result<Unit> {
        if (presentedTaskId != taskId) return Result.success(Unit)
        return restoreForeground()
    }

    fun currentTaskId(): Int? = presentedTaskId

    fun restoreForeground(): Result<Unit> {
        val taskId = presentedTaskId ?: return Result.success(Unit)
        val presenter = activePresenter ?: return Result.success(Unit)
        return presenter.restore(taskId).onSuccess {
            if (presentedTaskId == taskId && activePresenter === presenter) {
                presentedTaskId = null
                activePresenter = null
            }
        }
    }

}
