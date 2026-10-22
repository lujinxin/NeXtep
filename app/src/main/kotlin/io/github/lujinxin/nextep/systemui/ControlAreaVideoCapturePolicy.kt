package io.github.lujinxin.nextep.systemui

import io.github.lujinxin.nextep.workspace.ControlAreaVideoContract

/** Retry one native window without extending deadlines or overriding a manual return. */
internal class ControlAreaVideoCapturePolicy {
    private var sourceId = 0L
    private var manuallyReleasedSourceId = 0L
    private var eligible = false
    private var nextAttemptAt: Long? = null
    var attemptCount = 0
        private set

    fun update(sourceId: Long, available: Boolean, embedded: Boolean, reason: String?, now: Long) {
        // An inactive host or a failed reply can report ID 0 without destroying the source.
        // Keep its budget and manual-return choice until a different native lifetime arrives.
        if (sourceId > 0L && this.sourceId != sourceId) {
            this.sourceId = sourceId
            attemptCount = 0
            nextAttemptAt = null
        }
        eligible = sourceId > 0L && available && !embedded && allowsCapture(reason)
        if (embedded) {
            // A successful source can need capture again after a native transition.
            attemptCount = 0
            nextAttemptAt = null
        }
        if (eligible && sourceId != manuallyReleasedSourceId &&
            attemptCount < MAX_ATTEMPTS && nextAttemptAt == null) {
            nextAttemptAt = now + INITIAL_DELAY_MS
        }
    }

    fun nextAutomaticDelay(now: Long): Long? {
        if (!eligible || sourceId == manuallyReleasedSourceId || attemptCount >= MAX_ATTEMPTS) return null
        return nextAttemptAt?.let { (it - now).coerceAtLeast(0L) }
    }

    fun recordAttempt(sourceId: Long, automatic: Boolean, now: Long): Boolean {
        if (automatic) {
            if (sourceId != this.sourceId || nextAutomaticDelay(now) != 0L) return false
        } else {
            this.sourceId = sourceId
            if (manuallyReleasedSourceId == sourceId) manuallyReleasedSourceId = 0L
            attemptCount = 0
        }
        attemptCount += 1
        nextAttemptAt = if (attemptCount < MAX_ATTEMPTS) now + RETRY_DELAYS_MS[attemptCount - 1] else null
        return true
    }

    fun recordManualReturn(sourceId: Long) {
        if (sourceId > 0L) manuallyReleasedSourceId = sourceId
        nextAttemptAt = null
    }

    fun reset(clearManualReturn: Boolean) {
        sourceId = 0L
        attemptCount = 0
        nextAttemptAt = null
        eligible = false
        if (clearManualReturn) manuallyReleasedSourceId = 0L
    }

    companion object {
        private const val INITIAL_DELAY_MS = 450L
        private const val MAX_ATTEMPTS = 4
        private val RETRY_DELAYS_MS = longArrayOf(750L, 1_500L, 3_000L)

        fun allowsCapture(reason: String?): Boolean =
            reason == null || reason == ControlAreaVideoContract.REASON_NOT_READY
    }
}
