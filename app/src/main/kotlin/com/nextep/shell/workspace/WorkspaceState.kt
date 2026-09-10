package com.nextep.shell.workspace

sealed interface WorkspaceState {
    data object Disabled : WorkspaceState
    data object Entering : WorkspaceState
    data class Active(val geometry: WorkspaceGeometry) : WorkspaceState
    data object Exiting : WorkspaceState
    data class Faulted(val reason: String) : WorkspaceState
}
