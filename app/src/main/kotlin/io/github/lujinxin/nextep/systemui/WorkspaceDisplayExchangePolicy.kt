package io.github.lujinxin.nextep.systemui

/** Reject mixed app, floating-window, rotation and lock transitions. */
internal object WorkspaceDisplayExchangePolicy {
    enum class Kind { EXCHANGE, HOME, WALLPAPER, STATIONARY_DISPLAY, STATIONARY_SLOT_DISPLAY, OTHER }
    data class Change(val kind: Kind, val rotates: Boolean = false)

    // ColorOS also reports cross-display moves as OPEN/CLOSE/TO_FRONT/TO_BACK.
    // The caller must still establish ownership of the exchanging task.
    fun isExchangeMode(mode: Int): Boolean = mode in setOf(1, 2, 3, 4, 6)

    fun shouldFinishImmediately(
        type: Int,
        changes: List<Change>,
        hasRecentSlotMove: Boolean = false,
    ): Boolean =
        type in setOf(1, 2, 3, 4, 6) &&
            (changes.any { it.kind == Kind.EXCHANGE } ||
                (hasRecentSlotMove && changes.any { it.kind == Kind.HOME } &&
                    changes.any { it.kind == Kind.STATIONARY_DISPLAY })) &&
            changes.all { !it.rotates && it.kind != Kind.OTHER }
}
