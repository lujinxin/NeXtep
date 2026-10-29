package io.github.lujinxin.nextep.trigger

internal object WorkspaceRecoveryPolicy {
    /** An old inactive reconciliation must not undo a newer entry. */
    fun shouldApply(requestedActive: Boolean, onlyIfInactive: Boolean, currentActive: Boolean): Boolean =
        requestedActive || !onlyIfInactive || !currentActive
}
