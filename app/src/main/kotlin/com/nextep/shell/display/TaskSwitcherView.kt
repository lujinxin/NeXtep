package com.nextep.shell.display

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.nextep.shell.logging.NeXtepLog

class TaskSwitcherView(
    context: Context,
    private val slotIndex: Int,
) : FrameLayout(context) {
    interface Listener {
        fun onSlotClicked(slotIndex: Int)
        fun onIntentDropped(slotIndex: Int, intent: Intent): Boolean
    }

    var textureView: TextureView = createTextureView()
        private set

    private val statusView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 20f
        gravity = Gravity.CENTER
        background = statusBackground()
    }
    private var listener: Listener? = null
    private var busy = false
    private var currentState: SlotState = SlotState.Empty
    private var waiting = false

    init {
        clipToOutline = false
        isClickable = true
        setBackgroundColor(Color.TRANSPARENT)
        addView(textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            statusView,
            LayoutParams(dp(30), dp(30), Gravity.CENTER),
        )
        showState(SlotState.Empty)
        setOnDragListener { _, event -> handleDrag(event) }
        setOnClickListener {
            if (!busy &&
                (currentState is SlotState.Ready || currentState is SlotState.Occupied)
            ) {
                listener?.onSlotClicked(slotIndex)
            }
        }
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun showState(state: SlotState) {
        if (state is SlotState.Occupied && state == currentState &&
            !waiting && isAttachedToWindow
        ) return
        waiting = false
        val previousState = currentState
        currentState = state
        when (state) {
            SlotState.Empty -> showReady(animated = previousState is SlotState.Occupied)
            is SlotState.Ready -> showReady(animated = previousState is SlotState.Occupied)
            is SlotState.Occupied -> {
                showOccupied(
                    animated = previousState !is SlotState.Occupied ||
                        previousState.taskId != state.taskId,
                )
            }
            is SlotState.Failed -> showReady(animated = previousState is SlotState.Occupied)
        }
    }

    fun showWaiting() {
        waiting = true
        textureView.animate().cancel()
        textureView.animate()
            .alpha(0f)
            .scaleX(CONTENT_EXIT_SCALE)
            .scaleY(CONTENT_EXIT_SCALE)
            .setDuration(TRANSITION_OUT_DURATION_MS)
            .setInterpolator(transitionInterpolator)
            .start()
        statusView.animate().cancel()
        statusView.visibility = GONE
    }

    fun setBusy(isBusy: Boolean) {
        busy = isBusy
        if (!isBusy) {
            showState(currentState)
        }
    }

    fun replaceTextureView(listener: TextureView.SurfaceTextureListener): TextureView {
        waiting = true
        val previous = textureView
        previous.animate().cancel()
        previous.surfaceTextureListener = null
        removeView(previous)

        return createTextureView().also { replacement ->
            replacement.surfaceTextureListener = listener
            textureView = replacement
            addView(
                replacement,
                0,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    private fun handleDrag(event: DragEvent): Boolean = when (event.action) {
        DragEvent.ACTION_DRAG_STARTED -> {
            val accepted = !busy && currentState !is SlotState.Occupied &&
                AppDragContract.accepts(event.clipDescription)
            NeXtepLog.info(
                "slot_drag",
                "slot=$slotIndex started accepted=$accepted busy=$busy state=$currentState " +
                    "mime=${event.clipDescription}",
            )
            accepted
        }
        DragEvent.ACTION_DRAG_ENTERED -> {
            if (!busy) showReady(highlighted = true)
            true
        }
        DragEvent.ACTION_DRAG_EXITED -> {
            showState(currentState)
            true
        }
        DragEvent.ACTION_DROP -> {
            val intent = AppDragContract.readLaunchIntent(event.clipData)
            val handled = intent != null && !busy &&
                listener?.onIntentDropped(slotIndex, intent) == true
            NeXtepLog.info(
                "slot_drag",
                "slot=$slotIndex drop handled=$handled component=${intent?.component}",
            )
            handled
        }
        DragEvent.ACTION_DRAG_ENDED -> {
            if (!busy) showState(currentState)
            true
        }
        else -> true
    }

    private fun showReady(highlighted: Boolean = false, animated: Boolean = false) {
        textureView.animate().cancel()
        if (animated) {
            textureView.animate()
                .alpha(0f)
                .scaleX(CONTENT_EXIT_SCALE)
                .scaleY(CONTENT_EXIT_SCALE)
                .setDuration(TRANSITION_OUT_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .start()
        } else {
            textureView.alpha = 0f
            textureView.scaleX = 1f
            textureView.scaleY = 1f
        }
        statusView.text = "+"
        statusView.textSize = 20f
        statusView.setPadding(0, 0, 0, dp(2))
        statusView.layoutParams = LayoutParams(dp(30), dp(30), Gravity.CENTER)
        statusView.background = statusBackground(
            if (highlighted) Color.argb(210, 28, 112, 121) else Color.argb(158, 22, 40, 48),
        )
        statusView.visibility = VISIBLE
        statusView.animate().cancel()
        if (animated) {
            statusView.alpha = 0f
            statusView.scaleX = STATUS_ENTER_SCALE
            statusView.scaleY = STATUS_ENTER_SCALE
            statusView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(STATUS_ENTER_DELAY_MS)
                .setDuration(TRANSITION_IN_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .start()
        } else {
            statusView.alpha = 1f
            statusView.scaleX = 1f
            statusView.scaleY = 1f
        }
    }

    private fun showOccupied(animated: Boolean) {
        statusView.animate().cancel()
        if (animated && statusView.visibility == View.VISIBLE) {
            statusView.animate()
                .alpha(0f)
                .scaleX(STATUS_EXIT_SCALE)
                .scaleY(STATUS_EXIT_SCALE)
                .setStartDelay(0L)
                .setDuration(TRANSITION_OUT_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .withEndAction { statusView.visibility = GONE }
                .start()
        } else {
            statusView.visibility = GONE
        }

        textureView.animate().cancel()
        if (animated) {
            textureView.alpha = 0f
            textureView.scaleX = CONTENT_ENTER_SCALE
            textureView.scaleY = CONTENT_ENTER_SCALE
            textureView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(TRANSITION_IN_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .start()
        } else {
            textureView.alpha = 1f
            textureView.scaleX = 1f
            textureView.scaleY = 1f
        }
    }

    private fun statusBackground(color: Int = Color.argb(158, 22, 40, 48)) =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun createTextureView(): TextureView = TextureView(context)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private val transitionInterpolator = DecelerateInterpolator(1.6f)

    private companion object {
        const val TRANSITION_OUT_DURATION_MS = 120L
        const val TRANSITION_IN_DURATION_MS = 220L
        const val STATUS_ENTER_DELAY_MS = 70L
        const val CONTENT_ENTER_SCALE = 0.96f
        const val CONTENT_EXIT_SCALE = 0.98f
        const val STATUS_ENTER_SCALE = 0.82f
        const val STATUS_EXIT_SCALE = 0.88f
    }

}
