package io.github.lujinxin.nextep.display

/** Fast recovery around task changes, with a slow fallback when Shell callbacks are absent. */
internal class WorkspaceRefreshPolicy {
    private var settleUntil = 0L

    fun onTaskChanged(now: Long) {
        settleUntil = now + SETTLE_WINDOW_MS
    }

    fun delayMs(now: Long, pendingRecovery: Boolean = false): Long =
        if (pendingRecovery || now < settleUntil) FAST_INTERVAL_MS else IDLE_INTERVAL_MS

    companion object {
        const val FAST_INTERVAL_MS = 120L
        const val IDLE_INTERVAL_MS = 1_000L
        private const val SETTLE_WINDOW_MS = 2_000L
    }
}
