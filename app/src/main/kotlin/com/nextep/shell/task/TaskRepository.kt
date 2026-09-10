package com.nextep.shell.task

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.view.Display
import android.os.UserHandle
import com.nextep.shell.framework.TaskInfoCompat
import com.nextep.shell.logging.NeXtepLog

class TaskRepository(context: Context) {
    data class TaskSnapshot(
        val taskId: Int,
        val displayId: Int,
        val component: ComponentName,
        val bounds: Rect,
        val windowingMode: Int,
        val resizeMode: Int?,
        val densityDpi: Int,
        val token: Any?,
        val userId: Int?,
    )

    private val activityManager = context.getSystemService(ActivityManager::class.java)

    fun foregroundTask(): TaskSnapshot? = try {
        runningTasks().firstOrNull { snapshot ->
            snapshot.displayId == Display.DEFAULT_DISPLAY
        }
    } catch (error: Throwable) {
        NeXtepLog.error("task_repository", "Foreground task query failed", error)
        null
    }

    fun findTask(taskId: Int): TaskSnapshot? = try {
        runningTasks().firstOrNull { it.taskId == taskId }
    } catch (error: Throwable) {
        NeXtepLog.error("task_repository", "Task lookup failed taskId=$taskId", error)
        null
    }

    fun findTaskForComponent(
        component: ComponentName,
        userHandle: UserHandle? = null,
    ): TaskSnapshot? {
        val tasks = runningTasks().let { snapshots ->
            val userId = userHandle?.let(TaskInfoCompat::userIdentifier) ?: return@let snapshots
            snapshots.filter { it.userId == userId }
        }
        return tasks.firstOrNull { it.component == component }
            ?: tasks.firstOrNull { it.component.packageName == component.packageName }
    }

    fun bringTaskToFront(taskId: Int): Result<Unit> = runCatching {
        val manager = checkNotNull(activityManager) { "ActivityManager unavailable" }
        manager.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME)
        NeXtepLog.info("task_repository", "Brought taskId=$taskId to front")
    }

    @Suppress("DEPRECATION")
    fun runningTasks(): List<TaskSnapshot> = try {
        activityManager?.getRunningTasks(MAX_TASK_QUERY)
            ?.mapNotNull(::snapshot)
            .orEmpty()
    } catch (error: Throwable) {
        NeXtepLog.error("task_repository", "Running task query failed", error)
        emptyList()
    }

    private fun snapshot(info: ActivityManager.RunningTaskInfo): TaskSnapshot? {
        val component = info.topActivity ?: info.baseActivity ?: return null
        val windowState = TaskInfoCompat.readWindowState(info) ?: return null
        return TaskSnapshot(
            taskId = info.taskId,
            displayId = windowState.displayId,
            component = component,
            bounds = windowState.bounds,
            windowingMode = windowState.windowingMode,
            resizeMode = windowState.resizeMode,
            densityDpi = windowState.densityDpi,
            token = windowState.token,
            userId = TaskInfoCompat.readUserId(info),
        )
    }

    private companion object {
        const val MAX_TASK_QUERY = 64
    }
}
