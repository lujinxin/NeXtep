package io.github.lujinxin.nextep.systemui

import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** Recognizes one deliberate pinch/spread per physical touchscreen contact sequence. */
internal class FourFingerGestureDetector(private val minimumSpanChangePx: Float) {
    enum class Action(val active: Boolean) { ENTER(true), EXIT(false) }

    private val pointerIds = IntArray(FINGER_COUNT)
    private val initialRadii = FloatArray(FINGER_COUNT)
    private val x = FloatArray(FINGER_COUNT)
    private val y = FloatArray(FINGER_COUNT)
    private var started = false
    private var abandoned = true
    private var tracking = false
    private var downAt = 0L
    private var trackingAt = 0L
    private var initialCenterX = 0f
    private var initialCenterY = 0f
    private var initialSpan = 0f

    fun observe(event: MotionEvent, allowed: Boolean): Action? {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                started = true
                abandoned = false
                downAt = event.eventTime
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                reset()
                return null
            }
        }
        if (!started || abandoned) return null
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) ||
            event.pointerCount > FINGER_COUNT || event.actionMasked == MotionEvent.ACTION_POINTER_UP ||
            (0 until event.pointerCount).any { event.getToolType(it) != MotionEvent.TOOL_TYPE_FINGER }
        ) {
            abandon()
            return null
        }
        if (!tracking) {
            if (event.actionMasked != MotionEvent.ACTION_POINTER_DOWN || event.pointerCount != FINGER_COUNT) return null
            if (!allowed || event.eventTime - downAt > ASSEMBLE_TIMEOUT_MS) {
                abandon()
                return null
            }
            for (index in 0 until FINGER_COUNT) {
                pointerIds[index] = event.getPointerId(index)
                x[index] = event.getX(index)
                y[index] = event.getY(index)
            }
            initialCenterX = x.average().toFloat()
            initialCenterY = y.average().toFloat()
            var squaredSpan = 0f
            for (index in 0 until FINGER_COUNT) {
                val radius = hypot(x[index] - initialCenterX, y[index] - initialCenterY)
                initialRadii[index] = radius
                squaredSpan += radius * radius
            }
            initialSpan = sqrt(squaredSpan / FINGER_COUNT)
            if (initialSpan < minimumSpanChangePx * 1.5f) {
                abandon()
                return null
            }
            trackingAt = event.eventTime
            tracking = true
            return null
        }
        if (event.actionMasked != MotionEvent.ACTION_MOVE || event.pointerCount != FINGER_COUNT) return null
        if (event.eventTime - trackingAt > GESTURE_TIMEOUT_MS) {
            abandon()
            return null
        }
        for (index in 0 until FINGER_COUNT) {
            val pointerIndex = event.findPointerIndex(pointerIds[index])
            if (pointerIndex < 0) {
                abandon()
                return null
            }
            x[index] = event.getX(pointerIndex)
            y[index] = event.getY(pointerIndex)
        }
        val centerX = x.average().toFloat()
        val centerY = y.average().toFloat()
        // A four-finger pan must not become a pinch because one pointer lagged behind.
        if (hypot(centerX - initialCenterX, centerY - initialCenterY) >
            maxOf(minimumSpanChangePx * 2f, initialSpan * 0.4f)
        ) {
            abandon()
            return null
        }
        var squaredSpan = 0f
        for (index in 0 until FINGER_COUNT) {
            val radius = hypot(x[index] - centerX, y[index] - centerY)
            squaredSpan += radius * radius
        }
        val spanChange = sqrt(squaredSpan / FINGER_COUNT) - initialSpan
        val threshold = maxOf(minimumSpanChangePx, initialSpan * REQUIRED_SCALE_CHANGE)
        if (!spanChange.isFinite()) {
            abandon()
            return null
        }
        if (abs(spanChange) < threshold) return null
        val direction = if (spanChange < 0f) -1f else 1f
        val agreeingFingers = (0 until FINGER_COUNT).count { index ->
            (hypot(x[index] - centerX, y[index] - centerY) - initialRadii[index]) * direction >=
                minimumSpanChangePx * 0.3f
        }
        if (agreeingFingers < 3) return null
        // Direction determines the action. SystemUI checks its current state only
        // after recognition, so an asynchronous exit cannot reverse the next pinch.
        val confirmed = if (spanChange < 0f) Action.ENTER else Action.EXIT
        abandon()
        return confirmed
    }

    fun abandon() {
        abandoned = true
        tracking = false
    }

    fun reset() {
        started = false
        abandoned = true
        tracking = false
    }

    private companion object {
        const val FINGER_COUNT = 4
        const val ASSEMBLE_TIMEOUT_MS = 400L
        const val GESTURE_TIMEOUT_MS = 1_200L
        const val REQUIRED_SCALE_CHANGE = 0.22f
    }
}
