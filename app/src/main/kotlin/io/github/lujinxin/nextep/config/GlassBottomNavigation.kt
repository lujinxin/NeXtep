package io.github.lujinxin.nextep.config

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.logging.NeXtepLog
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Native View adaptation of Miuix's press, drag, release and lens navigation behavior. */
internal class GlassBottomNavigation(
    context: Context,
    private val source: GlassPageContent,
    private val onSelected: (Int) -> Unit,
) : FrameLayout(context), Choreographer.FrameCallback {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val barBounds = RectF()
    private val renderer = LiquidGlassRenderer(context)
    private val items = listOf(
        NavigationTab(context, R.string.navigation_settings, R.drawable.ic_navigation_settings),
        NavigationTab(context, R.string.navigation_about, R.drawable.ic_navigation_about),
    )
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        items.forEachIndexed { index, item ->
            addView(item, LinearLayout.LayoutParams(0, -1, 1f))
            item.setOnClickListener { activate(index) }
        }
    }
    private val position = SpringValue(0f, 1000f, 1f)
    private val press = SpringValue(0f, 1000f, 1f)
    private val lensScaleX = SpringValue(1f, 250f, 0.6f)
    private val lensScaleY = SpringValue(1f, 250f, 0.7f)
    private val panelOffset = SpringValue(0f, 300f, 1f)
    private var framePosted = false
    private var previousFrame = 0L
    private var releaseWhenSettled = false
    private var releaseNotBefore = 0L
    private var activePointer = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downIndex = 0f
    private var gestureCancelled = false
    private var observedTree: ViewTreeObserver? = null
    private var backdropDirty = true
    private var captureWarningLogged = false
    private val sourceLocation = IntArray(2)
    private val ownLocation = IntArray(2)
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { refreshBackdrop() }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { refreshBackdrop() }
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (source.isDirty) refreshBackdrop()
        true
    }
    private fun captureBackdrop() {
        if (!backdropDirty || width <= 0 || height <= 0 || !source.isShown ||
            !source.backdrop.hasDisplayList()) return
        runCatching {
            source.getLocationInWindow(sourceLocation)
            getLocationInWindow(ownLocation)
            renderer.capture(source.backdrop, width, height,
                sourceLocation[0] - ownLocation[0], sourceLocation[1] - ownLocation[1])
            backdropDirty = false
        }.onFailure {
            if (!captureWarningLogged) {
                captureWarningLogged = true
                NeXtepLog.warn("settings_glass", "Could not record the page backdrop", it)
            }
        }
    }

    var selectedPosition: Int = 0
        private set

    init {
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        elevation = dp(4f)
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(barBounds.left.roundToInt(), barBounds.top.roundToInt(),
                    barBounds.right.roundToInt(), barBounds.bottom.roundToInt(), barBounds.height() / 2f)
                outline.alpha = 0.12f
            }
        }
        addView(row, LayoutParams(-1, -1, Gravity.CENTER))
        updateSelectedItems()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val barHeight = dp(52f) + 12f * resources.displayMetrics.scaledDensity
        val top = (h - barHeight) / 2f
        barBounds.set(0f, top, w.toFloat(), top + barHeight)
        row.layoutParams = LayoutParams((w - dp(8f)).roundToInt().coerceAtLeast(1),
            (barHeight - dp(8f)).roundToInt(), Gravity.CENTER)
        invalidateOutline()
        refreshBackdrop()
    }

    fun select(index: Int, animate: Boolean = true) {
        val next = index.coerceIn(items.indices)
        val changed = selectedPosition != next
        selectedPosition = next
        updateSelectedItems()
        if (activePointer != MotionEvent.INVALID_POINTER_ID) return
        if (!animate) {
            position.snap(next.toFloat())
            resetPress()
        } else if (changed) {
            position.target = next.toFloat()
            beginPress()
            releaseWhenSettled = true
            releaseNotBefore = SystemClock.uptimeMillis() + 80L
        }
        requestFrame()
        refreshBackdrop()
    }

    /** A page swipe drives the lens continuously; a navigation drag owns its own position. */
    fun followPageProgress(value: Float) {
        if (activePointer != MotionEvent.INVALID_POINTER_ID) return
        position.snap(value.coerceIn(0f, items.lastIndex.toFloat()))
        resetPress()
        invalidate()
    }

    private fun activate(index: Int) {
        val next = index.coerceIn(items.indices)
        val changed = selectedPosition != next
        selectedPosition = next
        updateSelectedItems()
        position.target = next.toFloat()
        if (activePointer == MotionEvent.INVALID_POINTER_ID) {
            beginPress()
            releaseWhenSettled = true
            releaseNotBefore = SystemClock.uptimeMillis() + 80L
        }
        requestFrame()
        // Commit immediately. Motion never blocks another tap or delays changing the page.
        if (changed) onSelected(next)
        refreshBackdrop()
    }

    private fun updateSelectedItems() {
        items.forEachIndexed { index, item -> item.isSelected = index == selectedPosition }
    }

    private fun indexAt(x: Float): Int {
        val logicalX = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) width - x else x
        return ((logicalX - dp(4f)) / slotWidth()).toInt().coerceIn(items.indices)
    }

    private fun slotWidth() = ((width - dp(8f)) / items.size).coerceAtLeast(1f)

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        return event.actionMasked == MotionEvent.ACTION_DOWN && barBounds.contains(event.x, event.y)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!barBounds.contains(event.x, event.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                activePointer = event.getPointerId(0)
                downX = event.x
                downIndex = indexAt(downX).toFloat()
                gestureCancelled = false
                position.target = downIndex
                beginPress()
                requestFrame()
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointer)
                if (pointerIndex < 0 || gestureCancelled) return true
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                if (y < barBounds.top - dp(48f) || y > barBounds.bottom + dp(48f)) {
                    gestureCancelled = true
                    finishGesture(cancelled = true)
                    return true
                }
                val direction = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) -1f else 1f
                val delta = x - downX
                position.target = (downIndex + direction * delta / slotWidth())
                    .coerceIn(0f, items.lastIndex.toFloat())
                panelOffset.target = dp(4f) * (delta / width.coerceAtLeast(1)).coerceIn(-1f, 1f)
                requestFrame()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointer) {
                    val replacement = (0 until event.pointerCount).firstOrNull { it != event.actionIndex }
                    if (replacement == null) finishGesture(cancelled = true)
                    else {
                        activePointer = event.getPointerId(replacement)
                        downX = event.getX(replacement)
                        downIndex = position.target
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val pointerIndex = event.findPointerIndex(activePointer)
                if (pointerIndex >= 0 && !gestureCancelled) {
                    val direction = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) -1f else 1f
                    position.target = (downIndex + direction * (event.getX(pointerIndex) - downX) / slotWidth())
                        .coerceIn(0f, items.lastIndex.toFloat())
                    items[position.target.roundToInt()].performClick()
                    performClick()
                }
                finishGesture(cancelled = gestureCancelled)
            }
            MotionEvent.ACTION_CANCEL -> finishGesture(cancelled = true)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun beginPress() {
        releaseWhenSettled = false
        press.target = 1f
        lensScaleX.target = 78f / 56f
        lensScaleY.target = 78f / 56f
    }

    private fun finishGesture(cancelled: Boolean) {
        if (cancelled) position.target = selectedPosition.toFloat()
        activePointer = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
        panelOffset.target = 0f
        releaseWhenSettled = true
        releaseNotBefore = SystemClock.uptimeMillis() + 16L
        requestFrame()
    }

    private fun resetPress() {
        releaseWhenSettled = false
        press.snap(0f)
        lensScaleX.snap(1f)
        lensScaleY.snap(1f)
        panelOffset.snap(0f)
    }

    private fun requestFrame() {
        if (!isAttachedToWindow || !isShown || framePosted) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        framePosted = false
        val dt = if (previousFrame == 0L) 1f / 60f
            else ((frameTimeNanos - previousFrame) / 1_000_000_000f).coerceIn(0f, 0.032f)
        previousFrame = frameTimeNanos
        if (releaseWhenSettled && SystemClock.uptimeMillis() >= releaseNotBefore &&
            abs(position.value - position.target) < 0.025f) {
            releaseWhenSettled = false
            press.target = 0f
            lensScaleX.target = 1f
            lensScaleY.target = 1f
        }
        val values = listOf(position, press, lensScaleX, lensScaleY, panelOffset)
        if (!ValueAnimator.areAnimatorsEnabled()) {
            values.forEach { it.snap(it.target) }
            if (activePointer == MotionEvent.INVALID_POINTER_ID) resetPress()
        } else values.forEach { it.advance(dt) }
        invalidate()
        if (releaseWhenSettled || values.any { !it.settled }) requestFrame()
        else previousFrame = 0L
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (barBounds.isEmpty) return
        // The page is drawn first as a sibling. Sample its RenderNode in this same
        // traversal rather than redrawing scrolling views from a delayed callback.
        if (canvas.isHardwareAccelerated) captureBackdrop()
        val progress = press.value.coerceIn(0f, 1f)
        val panelScale = 1f + dp(16f) / width.coerceAtLeast(1) * progress
        val save = canvas.save()
        canvas.translate(panelOffset.value, 0f)
        canvas.scale(panelScale, panelScale, barBounds.centerX(), barBounds.centerY())
        renderer.drawBase(canvas, barBounds)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
        val visualPosition = position.value.coerceIn(0f, items.lastIndex.toFloat())
        val physicalPosition = if (layoutDirection == View.LAYOUT_DIRECTION_RTL)
            items.lastIndex - visualPosition else visualPosition
        val left = dp(4f) + physicalPosition * slotWidth()
        val lensBounds = RectF(left, barBounds.top + dp(4f), left + slotWidth(), barBounds.bottom - dp(4f))
        val stretch = (position.velocity / 10f).coerceIn(-0.2f, 0.2f)
        renderer.drawSelection(canvas, barBounds, lensBounds, progress,
            lensScaleX.value / (1f - stretch * 0.75f), lensScaleY.value * (1f - stretch * 0.25f),
            panelOffset.value, panelScale) { recording ->
            items.forEach { item ->
                val saved = recording.save()
                recording.translate((row.left + item.left).toFloat(), (row.top + item.top).toFloat())
                val scale = 1f + 0.2f * progress
                recording.scale(scale, scale, item.width / 2f, item.height / 2f)
                item.drawAccent(recording)
                recording.restoreToCount(saved)
            }
        }
    }

    fun refreshBackdrop() {
        backdropDirty = true
        if (isAttachedToWindow && isShown) invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observedTree = source.viewTreeObserver.also {
            it.addOnScrollChangedListener(scrollListener)
            it.addOnGlobalLayoutListener(layoutListener)
            it.addOnPreDrawListener(preDrawListener)
        }
        refreshBackdrop()
        requestFrame()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!isAttachedToWindow) return
        if (visibility == View.VISIBLE) {
            refreshBackdrop()
            requestFrame()
        } else stopMotion()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // The framework can call this during super construction.
        if (changedView !== this || !isAttachedToWindow) return
        if (visibility == View.VISIBLE) {
            refreshBackdrop()
            requestFrame()
        } else stopMotion()
    }

    private fun stopMotion() {
        Choreographer.getInstance().removeFrameCallback(this)
        framePosted = false
        previousFrame = 0L
        activePointer = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
        position.snap(selectedPosition.toFloat())
        resetPress()
    }

    override fun onDetachedFromWindow() {
        stopMotion()
        backdropDirty = true
        observedTree?.takeIf { it.isAlive }?.let {
            it.removeOnScrollChangedListener(scrollListener)
            it.removeOnGlobalLayoutListener(layoutListener)
            it.removeOnPreDrawListener(preDrawListener)
        }
        observedTree = null
        renderer.clear()
        super.onDetachedFromWindow()
    }
}

private class NavigationTab(context: Context, label: Int, icon: Int) : LinearLayout(context) {
    private val glyph = ImageView(context).apply {
        setImageResource(icon)
        imageTintList = android.content.res.ColorStateList.valueOf(SettingsPalette.text(context))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val title = TextView(context).apply {
        setText(label)
        textSize = 11f
        includeFontPadding = false
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setTextColor(SettingsPalette.text(context))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val accentGlyph = AppCompatResources.getDrawable(context, icon)!!.mutate().apply {
        setTint(SettingsPalette.primary(context))
    }
    private val accentPaint = Paint(title.paint).apply { color = SettingsPalette.primary(context) }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = resources.getString(label)
        val density = resources.displayMetrics.density
        val iconSize = (22f * density).roundToInt()
        addView(glyph, LayoutParams(iconSize, iconSize))
        addView(title, LayoutParams(-2, -2).apply { topMargin = density.roundToInt() })
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.app.ActionBar\$Tab"
        info.isSelected = isSelected
    }

    fun drawAccent(canvas: Canvas) {
        accentGlyph.setBounds(glyph.left, glyph.top, glyph.right, glyph.bottom)
        accentGlyph.draw(canvas)
        canvas.drawText(title.text.toString(), title.left.toFloat(), (title.top + title.baseline).toFloat(), accentPaint)
    }
}

/** Retargetable spring: new input changes targets without animation locks. */
private class SpringValue(initial: Float, private val stiffness: Float, ratio: Float) {
    var value = initial
        private set
    var velocity = 0f
        private set
    var target = initial
    private val damping = 2f * ratio * sqrt(stiffness)
    val settled get() = abs(value - target) < 0.001f && abs(velocity) < 0.01f

    fun snap(next: Float) {
        value = next
        target = next
        velocity = 0f
    }

    fun advance(dt: Float) {
        val steps = ceil(dt * 120f).toInt().coerceAtLeast(1)
        val step = dt / steps
        repeat(steps) {
            velocity += (stiffness * (target - value) - damping * velocity) * step
            value += velocity * step
        }
        if (settled) snap(target)
    }
}
