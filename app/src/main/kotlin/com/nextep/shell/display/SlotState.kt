package com.nextep.shell.display

import android.content.ComponentName

const val WORKSPACE_SLOT_COUNT = 3

sealed interface SlotState {
    data object Empty : SlotState

    data class Ready(val displayId: Int) : SlotState

    data class Occupied(
        val displayId: Int,
        val taskId: Int,
        val component: ComponentName,
    ) : SlotState

    data class Failed(val message: String) : SlotState
}
