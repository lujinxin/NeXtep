package com.nextep.shell.task

import com.nextep.shell.framework.TaskSurfaceCompat
import com.nextep.shell.workspace.WorkspaceGeometry

class SurfaceTaskPresenter : MainTaskPresenter {
    override fun present(taskId: Int, geometry: WorkspaceGeometry): Result<Unit> =
        TaskSurfaceCompat.present(taskId, geometry)

    override fun restore(taskId: Int): Result<Unit> = TaskSurfaceCompat.restore(taskId)

    override fun reapply(taskId: Int, geometry: WorkspaceGeometry): Result<Unit> =
        TaskSurfaceCompat.reapply(taskId, geometry)
}
