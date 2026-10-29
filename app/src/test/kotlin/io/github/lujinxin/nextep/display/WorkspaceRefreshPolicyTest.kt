package io.github.lujinxin.nextep.display

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceRefreshPolicyTest {
    @Test fun idleWorkspaceUsesSlowFallback() {
        assertEquals(1_000L, WorkspaceRefreshPolicy().delayMs(10_000L))
    }

    @Test fun taskChangeKeepsFastRecoveryForTwoSeconds() {
        val policy = WorkspaceRefreshPolicy()
        policy.onTaskChanged(10_000L)
        assertEquals(120L, policy.delayMs(10_000L))
        assertEquals(120L, policy.delayMs(11_999L))
        assertEquals(1_000L, policy.delayMs(12_000L))
    }

    @Test fun laterTaskChangeExtendsSettlingWindow() {
        val policy = WorkspaceRefreshPolicy()
        policy.onTaskChanged(10_000L)
        policy.onTaskChanged(11_500L)
        assertEquals(120L, policy.delayMs(13_000L))
        assertEquals(1_000L, policy.delayMs(13_500L))
    }

    @Test fun pendingLaunchOrOutputRecoveryStaysFastEvenAfterSettling() {
        val policy = WorkspaceRefreshPolicy()
        policy.onTaskChanged(10_000L)
        assertEquals(120L, policy.delayMs(20_000L, pendingRecovery = true))
        assertEquals(1_000L, policy.delayMs(20_000L, pendingRecovery = false))
    }
}
