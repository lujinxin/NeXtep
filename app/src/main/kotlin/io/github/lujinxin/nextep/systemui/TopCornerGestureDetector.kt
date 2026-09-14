package io.github.lujinxin.nextep.systemui

import android.os.SystemClock
import android.view.MotionEvent
import kotlin.math.abs

class TopCornerGestureDetector(
    density: Float,
    statusBarHeightPx: Float,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    enum class Result {
        IGNORED,
        OBSERVING,
        ABANDONED,
        CONFIRMED,
    }

    private val startHeight = maxOf(statusBarHeightPx, 52f * density)
    private val startWidth = 180f * density
    private val minimumLeftDistance = 36f * density
    private val maximumVerticalDistance = 56f * density
    private val maximumDurationMillis = 1_200L

    private var observing = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    fun observe(event: MotionEvent, screenWidth: Int): Result = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> onDown(event, screenWidth)
        MotionEvent.ACTION_MOVE -> onMove(event)
        MotionEvent.ACTION_UP,
        MotionEvent.ACTION_CANCEL -> finish(Result.ABANDONED)
        else -> if (observing) Result.OBSERVING else Result.IGNORED
    }

    private fun onDown(event: MotionEvent, screenWidth: Int): Result {
        observing = event.rawY in 0f..startHeight && event.rawX >= screenWidth - startWidth
        if (!observing) return Result.IGNORED
        downX = event.rawX
        downY = event.rawY
        downTime = clock()
        return Result.OBSERVING
    }

    private fun onMove(event: MotionEvent): Result {
        if (!observing) return Result.IGNORED
        val elapsed = clock() - downTime
        val leftDistance = downX - event.rawX
        val verticalDistance = abs(event.rawY - downY)

        if (elapsed > maximumDurationMillis ||
            verticalDistance > maximumVerticalDistance && verticalDistance > leftDistance
        ) {
            return finish(Result.ABANDONED)
        }
        if (leftDistance >= minimumLeftDistance && leftDistance > verticalDistance * 1.15f) {
            return finish(Result.CONFIRMED)
        }
        return Result.OBSERVING
    }

    private fun finish(result: Result): Result {
        if (!observing) return Result.IGNORED
        observing = false
        return result
    }
}
