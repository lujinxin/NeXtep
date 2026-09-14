package io.github.lujinxin.nextep.task

import android.graphics.Rect
import io.github.lujinxin.nextep.framework.ActivityTaskManagerCompat
import io.github.lujinxin.nextep.framework.WindowContainerTransactionCompat
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class FreeformTaskPresenter(
    private val taskRepository: TaskRepository,
) : MainTaskPresenter {
    private val originals = ConcurrentHashMap<Int, TaskRepository.TaskSnapshot>()

    override fun present(taskId: Int, geometry: WorkspaceGeometry): Result<Unit> = runCatching {
        val snapshot = taskRepository.findTask(taskId)
            ?: error("Task $taskId disappeared before presentation")
        originals.putIfAbsent(taskId, snapshot)

        ActivityTaskManagerCompat.setTaskResizeable(taskId, TASK_RESIZE_MODE_RESIZEABLE)
            .getOrThrow()
        val freeformMode = ActivityTaskManagerCompat
            .resolveWindowingMode("WINDOWING_MODE_FREEFORM")
            ?: error("Freeform windowing mode is unavailable")

        val targetBounds = Rect(
            geometry.contentLeft,
            geometry.topHeight,
            geometry.contentRight,
            geometry.screenHeight,
        )
        val targetDensityDpi = (
            snapshot.densityDpi * minOf(
                geometry.contentWidth.toFloat() / geometry.screenWidth,
                geometry.contentHeight.toFloat() / geometry.screenHeight,
            )
        ).roundToInt().coerceAtLeast(120)
        val token = snapshot.token ?: error("Task $taskId has no WindowContainerToken")
        WindowContainerTransactionCompat.applyTaskLayout(
            token = token,
            bounds = targetBounds,
            densityDpi = targetDensityDpi,
            windowingMode = freeformMode,
        ).getOrThrow()
        verifyPresentation(taskId, freeformMode, targetBounds, targetDensityDpi)
        NeXtepLog.info(
            "freeform_presenter",
            "Presented taskId=$taskId bounds=$targetBounds densityDpi=$targetDensityDpi",
        )
    }.onFailure { error ->
        NeXtepLog.warn("freeform_presenter", "Presentation failed; restoring taskId=$taskId", error)
        restore(taskId)
    }

    override fun restore(taskId: Int): Result<Unit> = runCatching {
        val original = originals[taskId] ?: return@runCatching
        val token = original.token ?: error("Task $taskId has no restore token")
        WindowContainerTransactionCompat.applyTaskLayout(
            token = token,
            bounds = original.bounds,
            densityDpi = original.densityDpi,
            windowingMode = original.windowingMode,
        ).getOrThrow()
        original.resizeMode?.let { resizeMode ->
            ActivityTaskManagerCompat.setTaskResizeable(taskId, resizeMode).getOrThrow()
        }
        originals.remove(taskId, original)
        NeXtepLog.info("freeform_presenter", "Restored taskId=$taskId")
    }

    override fun reapply(taskId: Int, geometry: WorkspaceGeometry): Result<Unit> =
        present(taskId, geometry)

    private fun verifyPresentation(
        taskId: Int,
        freeformMode: Int,
        targetBounds: Rect,
        targetDensityDpi: Int,
    ) {
        val applied = taskRepository.findTask(taskId)
            ?: error("Task $taskId disappeared after presentation")
        check(
            applied.windowingMode == freeformMode &&
                applied.bounds == targetBounds &&
                applied.densityDpi == targetDensityDpi
        ) {
            "Task $taskId rejected presentation: mode=${applied.windowingMode} " +
                "bounds=${applied.bounds} densityDpi=${applied.densityDpi}"
        }
    }

    private companion object {
        // Confirmed by `am task help` on the target ColorOS 16 device.
        const val TASK_RESIZE_MODE_RESIZEABLE = 2
    }
}
