package io.github.lujinxin.nextep.trigger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRecoveryPolicyTest {
    @Test fun delayedExitDoesNotUndoReentry() {
        assertFalse(WorkspaceRecoveryPolicy.shouldApply(false, true, true))
    }

    @Test fun explicitExitStillClosesAnActiveWorkspace() {
        assertTrue(WorkspaceRecoveryPolicy.shouldApply(false, false, true))
    }

    @Test fun inactiveRecoveryAndExplicitEntryRemainAvailable() {
        assertTrue(WorkspaceRecoveryPolicy.shouldApply(false, true, false))
        assertTrue(WorkspaceRecoveryPolicy.shouldApply(true, true, false))
        assertTrue(WorkspaceRecoveryPolicy.shouldApply(true, false, false))
    }
}
