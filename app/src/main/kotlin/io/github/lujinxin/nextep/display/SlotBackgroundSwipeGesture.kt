package io.github.lujinxin.nextep.display

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.SidebarSide
import kotlin.math.abs

/** Uses slot-local axes so an outward swipe also follows the rotated rail. */
internal class SlotBackgroundSwipeGesture(
    private val view: TaskSwitcherView,
    private val currentTask: () -> SlotTaskDrag?,
    private val onStarted: (SlotTaskDrag) -> Boolean,
    private val onDismiss: (SlotTaskDrag) -> Boolean,
    private val onEnded: () -> Unit,
) {
    private val configuration = ViewConfiguration.get(view.context)
    private val touchSlop = configuration.scaledTouchSlop.toFloat()
    private val minimumFlingVelocity = maxOf(
        configuration.scaledMinimumFlingVelocity.toFloat(),
        600f * view.resources.displayMetrics.density,
    )
    private var side = SidebarSide.RIGHT
    private var enabled = true
    private var downX = 0f
    private var downY = 0f
    private var task: SlotTaskDrag? = null
    private var tracking = false
    private var consumed = false
    private var active = false
    private var velocityTracker: VelocityTracker? = null

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        cancel()
    }

    fun setSide(side: SidebarSide) {
        cancel()
        this.side = side
    }

    fun touch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancel()
            tracking = true
            consumed = false
            downX = event.x
            downY = event.y
            task = currentTask()
            velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
            return false // Keep the existing click and long-press handling.
        }
        if (!tracking) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_CANCEL -> {
                val handled = consumed
                cancel()
                tracking = false
                return handled
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelViewTouch(event)
                cancel()
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                val dx = event.x - downX
                val dy = event.y - downY
                val direction = if (side == SidebarSide.RIGHT) 1f else -1f
                val outwardDistance = dx * direction
                val horizontal = abs(dx) > abs(dy) * 1.25f
                if (!consumed && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    // Any moved gesture must not fall through to a slot exchange,
                    // including an inward swipe or an unfinished outward swipe.
                    consumed = true
                    cancelViewTouch(event)
                    val candidate = task
                    // Still consume moved touches when disabled so a swipe cannot
                    // accidentally become a slot click. Clicks/long presses stay intact.
                    if (enabled && candidate != null && candidate == currentTask() && horizontal &&
                        outwardDistance > touchSlop && onStarted(candidate)
                    ) {
                        active = true
                        view.textureView.animate().cancel()
                        view.textureView.scaleX = 1f
                        view.textureView.scaleY = 1f
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (active) {
                    val offset = outwardDistance.coerceIn(0f, view.width.toFloat())
                    view.textureView.translationX = offset * direction
                    view.textureView.alpha = 1f - 0.6f * offset / view.width.coerceAtLeast(1)
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val handled = consumed
                    val candidate = task
                    velocityTracker?.computeCurrentVelocity(
                        1000, configuration.scaledMaximumFlingVelocity.toFloat(),
                    )
                    val outwardVelocity = (velocityTracker?.xVelocity ?: 0f) * direction
                    val distanceThreshold = maxOf(view.width * 0.35f, touchSlop * 2f)
                    val flingDistanceThreshold = maxOf(view.width * 0.15f, touchSlop * 2f)
                    try {
                        if (active && candidate != null && horizontal &&
                            (outwardDistance >= distanceThreshold ||
                                outwardDistance >= flingDistanceThreshold &&
                                outwardVelocity >= minimumFlingVelocity)
                        ) {
                            val dismissed = onDismiss(candidate)
                            NeXtepLog.info("slot_swipe", "slot=${candidate.slotIndex} background=$dismissed")
                        }
                    } catch (error: Throwable) {
                        NeXtepLog.warn("slot_swipe", "Could not move slot to background", error)
                    } finally {
                        // An untouched UP still belongs to View's click handler;
                        // cleanup must leave its pressed state intact.
                        tracking = false
                        cancel()
                    }
                    return handled
                }
            }
        }
        return consumed
    }

    /** Drain the current touch stream after a task, layout, or lifecycle change. */
    fun cancel() {
        val wasActive = active
        active = false
        task = null
        velocityTracker?.recycle()
        velocityTracker = null
        if (tracking) {
            consumed = true
            view.isPressed = false
            view.cancelLongPress()
        }
        if (wasActive) {
            view.textureView.translationX = 0f
            view.textureView.alpha = 1f
            view.parent?.requestDisallowInterceptTouchEvent(false)
            onEnded()
        }
    }

    private fun cancelViewTouch(event: MotionEvent) {
        val cancelEvent = MotionEvent.obtain(event)
        try {
            cancelEvent.action = MotionEvent.ACTION_CANCEL
            view.onTouchEvent(cancelEvent)
        } finally {
            cancelEvent.recycle()
        }
    }
}
