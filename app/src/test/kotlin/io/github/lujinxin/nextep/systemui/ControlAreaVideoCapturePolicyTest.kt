package io.github.lujinxin.nextep.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlAreaVideoCapturePolicyTest {
    @Test fun repeatedHostPublicationsDoNotPostponeInitialCapture() {
        val policy = ControlAreaVideoCapturePolicy()
        available(policy, now = 0L)
        for (now in 0L..448L step 16L) {
            available(policy, now)
            assertEquals(450L, now + requireNotNull(policy.nextAutomaticDelay(now)))
        }
        assertTrue(policy.recordAttempt(1L, automatic = true, now = 450L))
    }

    @Test fun temporaryNativeInputFailureCanRetryWithoutAnotherStatePublication() {
        val policy = ControlAreaVideoCapturePolicy()
        available(policy, now = 0L)
        assertTrue(policy.recordAttempt(1L, automatic = true, now = 450L))
        policy.update(1L, available = true, embedded = false, reason = "not_ready", now = 460L)
        val retryAt = 460L + requireNotNull(policy.nextAutomaticDelay(460L))
        assertTrue(policy.recordAttempt(1L, automatic = true, now = retryAt))
        assertEquals(2, policy.attemptCount)
    }

    @Test fun aLostReplyDoesNotConsumeTheOnlyAttempt() {
        val policy = ControlAreaVideoCapturePolicy()
        available(policy, now = 0L)
        assertTrue(policy.recordAttempt(1L, automatic = true, now = 450L))
        // No server reply: the original available state remains current.
        val retryAt = 450L + requireNotNull(policy.nextAutomaticDelay(450L))
        assertTrue(policy.recordAttempt(1L, automatic = true, now = retryAt))
    }

    @Test fun repeatedFailuresHaveAFiniteBudgetEvenWhenErrorRepliesOmitTheSourceId() {
        val policy = ControlAreaVideoCapturePolicy()
        var now = 0L
        available(policy, now)
        repeat(4) {
            now += requireNotNull(policy.nextAutomaticDelay(now))
            assertTrue(policy.recordAttempt(1L, automatic = true, now = now))
            policy.update(0L, available = false, embedded = false, reason = "unsupported", now = now)
            assertNull(policy.nextAutomaticDelay(now))
            available(policy, now)
        }
        assertEquals(4, policy.attemptCount)
        assertNull(policy.nextAutomaticDelay(now + 60_000L))
        assertFalse(policy.recordAttempt(1L, automatic = true, now = now + 60_000L))
    }

    @Test fun successfulCaptureAllowsRecoveryAfterANativeTransitionOfTheSameWindow() {
        val policy = ControlAreaVideoCapturePolicy()
        available(policy, now = 0L)
        assertTrue(policy.recordAttempt(1L, automatic = true, now = 450L))
        policy.update(1L, available = true, embedded = true, reason = null, now = 500L)
        assertNull(policy.nextAutomaticDelay(500L))
        policy.update(1L, available = true, embedded = false, reason = "transitioning", now = 800L)
        assertNull(policy.nextAutomaticDelay(800L))
        available(policy, now = 1_000L)
        val captureAt = 1_000L + requireNotNull(policy.nextAutomaticDelay(1_000L))
        assertTrue(policy.recordAttempt(1L, automatic = true, now = captureAt))
        assertEquals(1, policy.attemptCount)
    }

    @Test fun manualReturnSurvivesTemporaryRepliesAndLockUnlock() {
        val policy = ControlAreaVideoCapturePolicy()
        policy.update(1L, available = true, embedded = true, reason = null, now = 0L)
        policy.recordManualReturn(1L)
        available(policy, now = 100L)
        assertNull(policy.nextAutomaticDelay(100L))
        policy.reset(clearManualReturn = false)
        policy.update(0L, available = false, embedded = false, reason = null, now = 200L)
        available(policy, now = 300L)
        assertNull(policy.nextAutomaticDelay(10_000L))
        assertFalse(policy.recordAttempt(1L, automatic = true, now = 10_000L))
    }

    @Test fun quickButtonCanOverrideManualReturnAndRearmRecovery() {
        val policy = ControlAreaVideoCapturePolicy()
        available(policy, now = 0L)
        policy.recordManualReturn(1L)
        available(policy, now = 10L)
        assertNull(policy.nextAutomaticDelay(10L))
        assertTrue(policy.recordAttempt(1L, automatic = false, now = 20L))
        assertNotNull(policy.nextAutomaticDelay(20L))
        policy.update(1L, available = true, embedded = true, reason = null, now = 30L)
        assertNull(policy.nextAutomaticDelay(30L))
    }

    @Test fun aNewNativeWindowIsNotBlockedByReturningThePreviousOne() {
        val policy = ControlAreaVideoCapturePolicy()
        available(policy, now = 0L)
        policy.recordManualReturn(1L)
        policy.update(2L, available = true, embedded = false, reason = null, now = 20L)
        val captureAt = 20L + requireNotNull(policy.nextAutomaticDelay(20L))
        assertTrue(policy.recordAttempt(2L, automatic = true, now = captureAt))
    }

    @Test fun delayedRequestForAnOldWindowCannotConsumeTheNewWindowsBudget() {
        val policy = ControlAreaVideoCapturePolicy()
        available(policy, now = 0L)
        policy.update(2L, available = true, embedded = false, reason = null, now = 20L)
        assertFalse(policy.recordAttempt(1L, automatic = true, now = 470L))
        assertEquals(0, policy.attemptCount)
        assertTrue(policy.recordAttempt(2L, automatic = true, now = 470L))
    }

    @Test fun hardFailuresNeverTriggerAutomaticRetries() {
        for (reason in listOf("secure", "unsupported", "transitioning")) {
            val policy = ControlAreaVideoCapturePolicy()
            policy.update(1L, available = true, embedded = false, reason = reason, now = 0L)
            assertNull(policy.nextAutomaticDelay(10_000L))
            assertFalse(policy.recordAttempt(1L, automatic = true, now = 10_000L))
        }
    }

    @Test fun explicitlyLeavingTheWorkspaceAllowsAutomaticCaptureOnReentry() {
        val policy = ControlAreaVideoCapturePolicy()
        available(policy, now = 0L)
        policy.recordManualReturn(1L)
        policy.reset(clearManualReturn = true)
        available(policy, now = 100L)
        assertNotNull(policy.nextAutomaticDelay(100L))
    }

    @Test fun aManualRetryCanStartAFreshBudgetAfterAutomaticAttemptsExpire() {
        val policy = ControlAreaVideoCapturePolicy()
        var now = 0L
        available(policy, now)
        repeat(4) {
            now += requireNotNull(policy.nextAutomaticDelay(now))
            assertTrue(policy.recordAttempt(1L, automatic = true, now = now))
        }
        assertNull(policy.nextAutomaticDelay(now))
        assertTrue(policy.recordAttempt(1L, automatic = false, now = now + 1L))
        assertEquals(1, policy.attemptCount)
        assertNotNull(policy.nextAutomaticDelay(now + 1L))
    }

    private fun available(policy: ControlAreaVideoCapturePolicy, now: Long) {
        policy.update(1L, available = true, embedded = false, reason = null, now = now)
    }
}
