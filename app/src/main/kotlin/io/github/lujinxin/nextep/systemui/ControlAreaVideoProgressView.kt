package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.workspace.ControlAreaVideoState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** A media-session fallback. Its target is frozen for the whole scrub gesture. */
internal class ControlAreaVideoProgressView(
    context: Context,
    private val onSeek: (ControlAreaVideoState, Long) -> Unit,
    private val onTrackingChanged: (Boolean) -> Unit,
) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var state = ControlAreaVideoState()
    private var active = false
    private var trackingState: ControlAreaVideoState? = null
    private var touchInteraction = false
    private var cancelled = false
    private var pendingPosition: Long? = null
    private var pendingAt = 0L
    val isTracking: Boolean get() = trackingState != null

    private val elapsed = timeLabel(Gravity.START)
    private val duration = timeLabel(Gravity.END)
    private val slider = SeekBar(context).apply {
        max = PROGRESS_MAX
        progressDrawable = ProgressTrackDrawable(dp(3).toFloat())
        thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(dp(2), Color.rgb(61, 185, 232))
            setSize(dp(13), dp(13))
        }
        thumbOffset = dp(7)
        splitTrack = false
        minHeight = dp(24)
        setPadding(dp(7), 0, dp(7), 0)
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelled = false
                    touchInteraction = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> if (cancelled) return@setOnTouchListener true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) cancelled = true
                    parent?.requestDisallowInterceptTouchEvent(false)
                    touchInteraction = false
                    post { if (!touchInteraction && !isTracking) cancelled = false }
                }
            }
            false
        }
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                if (cancelled || !active || !canSeek(state)) return
                trackingState = state
                handler.removeCallbacks(tick)
                onTrackingChanged(true)
            }

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || cancelled) return
                val target = trackingState ?: state
                val position = positionFor(progress, target.durationMs)
                elapsed.text = formatTime(position)
                // Accessibility and keyboard adjustments do not begin a touch scrub.
                if (!isTracking && !touchInteraction && active && canSeek(target)) {
                    commitSeek(target, position)
                    onTrackingChanged(false)
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val target = trackingState
                trackingState = null
                if (!cancelled && active && target != null && sameTarget(target, state) && canSeek(state)) {
                    commitSeek(target, positionFor(seekBar.progress, target.durationMs))
                }
                onTrackingChanged(false)
                renderProgress()
                scheduleTick()
            }
        })
    }
    private val tick = Runnable {
        renderProgress()
        scheduleTick()
    }

    init {
        orientation = HORIZONTAL
        setPadding(dp(12), dp(2), dp(12), dp(2))
        background = VideoGlassMaterial(resources.displayMetrics.density)
        elevation = dp(4).toFloat()
        elapsed.setPadding(0, 0, dp(3), 0)
        duration.setPadding(dp(3), 0, 0, 0)
        addView(elapsed, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(slider, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(duration, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    fun bind(next: ControlAreaVideoState) {
        if (!sameTarget(state, next) || !canSeek(next)) {
            cancelTracking()
            pendingPosition = null
        }
        state = next
        slider.isEnabled = canSeek(next)
        slider.alpha = if (canSeek(next)) 1f else 0.48f
        slider.contentDescription = AppLanguage.string(context, R.string.control_video_seek)
        duration.text = formatTime(next.durationMs)
        val pending = pendingPosition
        if (pending != null && next.positionUpdatedAt >= pendingAt &&
            abs(estimatedPosition(includePending = false) - pending) < 2_000L) pendingPosition = null
        if (!isTracking) renderProgress()
        scheduleTick()
    }

    fun setControlsActive(value: Boolean) {
        active = value
        if (!value) cancelTracking()
        if (!isTracking) renderProgress()
        scheduleTick()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleTick()
    }

    override fun onDetachedFromWindow() {
        active = false
        cancelTracking()
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun commitSeek(target: ControlAreaVideoState, position: Long) {
        pendingPosition = position
        pendingAt = SystemClock.elapsedRealtime()
        onSeek(target, position)
        scheduleTick()
    }

    private fun cancelTracking() {
        if (isTracking) {
            trackingState = null
            cancelled = true
            slider.isPressed = false
            parent?.requestDisallowInterceptTouchEvent(false)
            onTrackingChanged(false)
        }
    }

    private fun estimatedPosition(includePending: Boolean = true): Long {
        val now = SystemClock.elapsedRealtime()
        val pending = pendingPosition
        val waiting = includePending && pending != null && now - pendingAt < 2_000L
        if (includePending && !waiting) pendingPosition = null
        val base = if (waiting) checkNotNull(pending) else state.positionMs.coerceAtLeast(0L)
        val updatedAt = if (waiting) pendingAt else state.positionUpdatedAt
        val speed = state.playbackSpeed.takeIf { it.isFinite() } ?: 0f
        val advance = if (updatedAt > 0L) (now - updatedAt).coerceAtLeast(0L).toDouble() * speed else 0.0
        return (base.toDouble() + advance).coerceIn(0.0, state.durationMs.coerceAtLeast(0L).toDouble()).toLong()
    }

    private fun renderProgress() {
        if (isTracking) return
        val position = estimatedPosition()
        elapsed.text = formatTime(position)
        slider.progress = if (state.durationMs > 0L) {
            (position.toDouble() / state.durationMs * PROGRESS_MAX).roundToInt().coerceIn(0, PROGRESS_MAX)
        } else 0
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tick)
        if (active && isAttachedToWindow && canSeek(state) && !isTracking &&
            (state.playbackSpeed != 0f || pendingPosition != null)) handler.postDelayed(tick, 250L)
    }

    private fun canSeek(value: ControlAreaVideoState) = value.canSeek && value.durationMs > 0L &&
        value.positionMs >= 0L && value.mediaSessionToken != null

    private fun sameTarget(left: ControlAreaVideoState, right: ControlAreaVideoState) =
        left.sourceId == right.sourceId && left.session == right.session &&
            left.mediaSessionToken == right.mediaSessionToken && left.mediaId == right.mediaId &&
            left.durationMs == right.durationMs

    private fun positionFor(progress: Int, duration: Long): Long =
        (duration.toDouble() * progress.coerceIn(0, PROGRESS_MAX) / PROGRESS_MAX).toLong()

    private fun timeLabel(alignment: Int) = TextView(context).apply {
        textSize = 11f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
        maxLines = 1
        gravity = alignment or Gravity.CENTER_VERTICAL
        setTextColor(if (alignment == Gravity.START) Color.WHITE else Color.rgb(186, 199, 211))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun formatTime(value: Long): String {
        val seconds = value.coerceAtLeast(0L) / 1_000L
        return if (seconds >= 3_600L) String.format(Locale.ROOT, "%d:%02d:%02d",
            seconds / 3_600L, seconds / 60L % 60L, seconds % 60L)
        else String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()


    private inner class ProgressTrackDrawable(private val thickness: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val trackColor = Color.argb(190, 222, 235, 245)
        private val progressColor = Color.rgb(89, 202, 244)
        private val track = RectF()
        private val played = RectF()

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            if (bounds.isEmpty) return
            val radius = thickness / 2f
            val centerY = bounds.exactCenterY()
            track.set(bounds.left.toFloat(), centerY - radius, bounds.right.toFloat(), centerY + radius)
            paint.color = trackColor
            canvas.drawRoundRect(track, radius, radius, paint)
            val fraction = level.coerceIn(0, 10_000) / 10_000f
            if (fraction <= 0f) return
            played.set(track.left, track.top, track.left + track.width() * fraction, track.bottom)
            paint.color = progressColor
            canvas.drawRoundRect(played, radius, radius, paint)
        }

        override fun onLevelChange(level: Int): Boolean {
            invalidateSelf()
            return true
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha.coerceIn(0, 255)
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val PROGRESS_MAX = 10_000
    }
}
