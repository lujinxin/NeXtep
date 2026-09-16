package io.github.lujinxin.nextep.task

import android.content.Context
import android.graphics.Rect
import io.github.lujinxin.nextep.framework.ActivityTaskManagerCompat
import io.github.lujinxin.nextep.framework.WindowContainerTransactionCompat
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry

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
        synchronizeFullscreenBounds(foreground, geometry).getOrThrow()
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

    fun restoreForeground(geometry: WorkspaceGeometry? = null): Result<Unit> {
        val taskId = presentedTaskId ?: return Result.success(Unit)
        val presenter = activePresenter ?: return Result.success(Unit)
        return presenter.restore(taskId).mapCatching {
            if (presentedTaskId == taskId && activePresenter === presenter) {
                presentedTaskId = null
                activePresenter = null
            }
            if (geometry != null) {
                taskRepository.findTask(taskId)?.let { task ->
                    synchronizeFullscreenBounds(task, geometry).getOrThrow()
                }
            }
        }
    }

    private fun synchronizeFullscreenBounds(
        task: TaskRepository.TaskSnapshot,
        geometry: WorkspaceGeometry,
    ): Result<Unit> {
        val fullscreenMode = ActivityTaskManagerCompat
            .resolveWindowingMode("WINDOWING_MODE_FULLSCREEN")
            ?: WINDOWING_MODE_FULLSCREEN
        if (task.windowingMode != fullscreenMode) return Result.success(Unit)

        val expectedBounds = Rect(0, 0, geometry.screenWidth, geometry.screenHeight)
        if (task.bounds == expectedBounds) return Result.success(Unit)

        NeXtepLog.info(
            "main_task_bounds",
            "Repairing taskId=${task.taskId} bounds=${task.bounds} expected=$expectedBounds",
        )
        return task.token?.let { token ->
            WindowContainerTransactionCompat.applyTaskBounds(token, expectedBounds)
        } ?: ActivityTaskManagerCompat.resizeTask(task.taskId, expectedBounds)
    }

    private companion object {
        const val WINDOWING_MODE_FULLSCREEN = 1
    }

}
