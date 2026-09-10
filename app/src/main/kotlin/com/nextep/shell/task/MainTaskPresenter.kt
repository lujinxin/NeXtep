package com.nextep.shell.task

import com.nextep.shell.workspace.WorkspaceGeometry

interface MainTaskPresenter {
    fun present(taskId: Int, geometry: WorkspaceGeometry): Result<Unit>
    fun restore(taskId: Int): Result<Unit>
    fun reapply(taskId: Int, geometry: WorkspaceGeometry): Result<Unit>
}
