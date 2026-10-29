package io.github.lujinxin.nextep.systemui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Region
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.ControlAreaVideoState
import io.github.lujinxin.nextep.workspace.WorkspaceControlMetrics
import java.lang.reflect.Proxy
import kotlin.math.abs

/** Two retained pages in the existing control window, measured and dragged in panel-local axes. */
internal class ControlAreaPager(context: Context, private val functions: TopAppStripView) :
    FrameLayout(context), WorkspacePanelRotationContent {
    private val video = ControlAreaVideoView(
        context,
        ::onVideoStateChanged,
        onReleaseRequested = { showPage(0) },
        canAutomaticallyCapture = {
            activePointerId == MotionEvent.INVALID_POINTER_ID && !functions.isInternalAppDragActive() &&
                settleAnimator?.isRunning != true
        },
    )
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minimumVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 2
    private val maximumVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pageIndicator = object : View(context) {
        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cy = height / 2f
            val cx = width / 2f
            repeat(2) { page ->
                val fraction = if (this@ControlAreaPager.width == 0) selectedPage.toFloat()
                    else pageOffset / this@ControlAreaPager.width
                val strength = (1f - abs(fraction - page)).coerceIn(0f, 1f)
                indicatorPaint.color = Color.argb((100 + 155 * strength).toInt(), 255, 255, 255)
                canvas.drawCircle(cx + (page - 0.5f) * dp(10f), cy, dp(2f), indicatorPaint)
            }
        }
    }
    private val boundaryDivider = View(context).apply {
        setBackgroundColor(Color.argb(90, 255, 255, 255))
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var hasVideoPage = false
    private var embedded = false
    private var selectedPage = 0
    private var pageOffset = 0f
    private var settleAnimator: ValueAnimator? = null
    private var dragging = false
    private var verticalGesture = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var gestureOffset = 0f
    private var velocity: VelocityTracker? = null
    private var landscape = false
    private var controlOnLeft = false
    private var insetsListener: Any? = null
    private var insetsObserver: ViewTreeObserver? = null

    init {
        clipChildren = true
        clipToPadding = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        functions.setOnVideoActionRequested {
            if (embedded) video.releaseVideo() else video.captureVideo()
        }
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Keep ordinary controls after the SurfaceView hole during transparent-region gathering.
        addView(functions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(pageIndicator, LayoutParams(dp(30f).toInt(), dp(8f).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        // The workspace boundary belongs to both pages and must never slide off with either one.
        addView(boundaryDivider, LayoutParams(LayoutParams.MATCH_PARENT, dp(1f).toInt().coerceAtLeast(1), Gravity.BOTTOM))
        updatePagePositions()
    }

    fun setLandscape(value: Boolean, isControlOnLeft: Boolean, refreshHost: Boolean = true) {
        if (landscape == value && controlOnLeft == isControlOnLeft) return
        landscape = value
        controlOnLeft = isControlOnLeft
        if (refreshHost) video.refreshHost()
        requestLayout()
    }

    override fun onPanelRotationChanged(rotationDegrees: Float) {
        video.setPanelRotation(rotationDegrees)
    }

    fun setWorkspaceState(visible: Boolean, locked: Boolean) {
        if (!visible || locked) {
            cancelGesture()
            showPage(0, animate = false)
        }
        video.setWorkspaceState(visible, locked)
    }

    fun refreshHost() = video.refreshHost()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        installTouchableStatusBarPassThrough()
        post { SystemUiStatusBarGestureInstaller.install(this) }
    }

    override fun onDetachedFromWindow() {
        cancelGesture()
        removeTouchableStatusBarPassThrough()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cancelGesture()
        pageOffset = selectedPage * w.toFloat()
        updatePagePositions()
        video.refreshHost()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
            activePointerId = event.getPointerId(0)
            downX = event.x
            downY = event.y
            gestureOffset = pageOffset
            verticalGesture = false
            dragging = settleAnimator?.isRunning == true
            settleAnimator?.cancel()
            settleAnimator = null
        } else if (event.actionMasked == MotionEvent.ACTION_POINTER_UP &&
            event.getPointerId(event.actionIndex) == activePointerId) {
            val replacement = if (event.actionIndex == 0) 1 else 0
            activePointerId = event.getPointerId(replacement)
            downX = event.getX(replacement)
            downY = event.getY(replacement)
            gestureOffset = pageOffset
            velocity?.clear()
        }
        velocity?.addMovement(event)
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            velocity?.recycle()
            velocity = null
            activePointerId = MotionEvent.INVALID_POINTER_ID
            dragging = false
        }
        return handled
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!hasVideoPage || functions.isInternalAppDragActive()) return false
        if (dragging) return true
        return tryStartPageDrag(event)
    }

    private fun tryStartPageDrag(event: MotionEvent): Boolean {
        if (!hasVideoPage || functions.isInternalAppDragActive()) return false
        if (event.actionMasked != MotionEvent.ACTION_MOVE || verticalGesture) return false
        val index = event.findPointerIndex(activePointerId)
        if (index < 0) return false
        val dx = event.getX(index) - downX
        val dy = event.getY(index) - downY
        if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
            verticalGesture = true
            return false
        }
        if (abs(dx) <= touchSlop || abs(dx) <= abs(dy) * 1.2f) return false
        val direction = if (dx < 0f) 1 else -1
        if (functions.canScrollHorizontallyAt(downX - functions.translationX, downY, direction)) return false
        if ((pageOffset <= 0f && dx > 0f) || (pageOffset >= width && dx < 0f)) return false
        dragging = true
        // Start at the handoff point, excluding distance already consumed by app scrolling.
        downX = event.getX(index)
        downY = event.getY(index)
        gestureOffset = pageOffset
        velocity?.clear()
        velocity?.addMovement(event)
        // A scrolling/dragging child may disallow interception. ViewGroup honors that
        // before reaching here; the app strip only releases it at its own scroll edge.
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!hasVideoPage) return super.onTouchEvent(event)
        val index = event.findPointerIndex(activePointerId)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                // Empty video/background areas have no child touch target; in that case
                // ViewGroup routes moves directly here without onInterceptTouchEvent().
                if (!dragging) tryStartPageDrag(event)
                if (dragging && index >= 0) {
                    pageOffset = (gestureOffset - (event.getX(index) - downX)).coerceIn(0f, width.toFloat())
                    updatePagePositions()
                }
            }
            MotionEvent.ACTION_UP -> {
                velocity?.computeCurrentVelocity(1000, maximumVelocity.toFloat())
                val vx = velocity?.getXVelocity(activePointerId) ?: 0f
                val target = if (dragging && abs(vx) > minimumVelocity) {
                    if (vx < 0f) 1 else 0
                } else if (width > 0 && pageOffset > width / 2f) 1 else 0
                showPage(target)
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                showPage(selectedPage)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun gatherTransparentRegion(region: Region?): Boolean {
        super.gatherTransparentRegion(region)
        // Translation-only paging does not trigger a new window transparency hint.
        // Keep alpha compositing for this small window instead of letting a stale
        // SurfaceView hole cull the functions page or the indicator after a layout.
        region?.setEmpty()
        return false
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = hasVideoPage
        if (hasVideoPage && selectedPage == 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        if (hasVideoPage && selectedPage == 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (hasVideoPage && action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD && selectedPage == 0) {
            showPage(1)
            return true
        }
        if (hasVideoPage && action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD && selectedPage == 1) {
            showPage(0)
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private fun onVideoStateChanged(state: ControlAreaVideoState) {
        val wasEmbedded = embedded
        embedded = state.embedded
        functions.setVideoActionState(canCapture = video.canCaptureVideo(), embedded = state.embedded)
        hasVideoPage = state.available || state.embedded || state.reason == "secure" || state.reason == "unsupported"
        if (!hasVideoPage) showPage(0, animate = false)
        else if (state.embedded && !wasEmbedded) showPage(1)
        updatePagePositions()
    }

    private fun showPage(page: Int, animate: Boolean = true) {
        selectedPage = if (hasVideoPage) page.coerceIn(0, 1) else 0
        settleAnimator?.cancel()
        settleAnimator = null
        val target = selectedPage * width.toFloat()
        if (!animate || width == 0 || abs(target - pageOffset) < 1f) {
            pageOffset = target
            updatePagePositions()
            return
        }
        settleAnimator = ValueAnimator.ofFloat(pageOffset, target).apply {
            duration = 220L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                pageOffset = it.animatedValue as Float
                updatePagePositions()
            }
            start()
        }
    }

    private fun updatePagePositions() {
        functions.translationX = -pageOffset
        // Never use GONE/INVISIBLE here: SurfaceView must retain the remote source.
        video.translationX = width - pageOffset
        video.setPageViewport(video.translationX, width, height)
        val videoSelected = selectedPage == 1 && hasVideoPage
        video.setPageActive(videoSelected && abs(pageOffset - width.toFloat()) < 1f)
        functions.importantForAccessibility = if (videoSelected) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else IMPORTANT_FOR_ACCESSIBILITY_AUTO
        video.importantForAccessibility = if (videoSelected) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        contentDescription = AppLanguage.string(context, if (videoSelected) R.string.control_page_video else R.string.control_page_functions)
        pageIndicator.visibility = if (hasVideoPage) VISIBLE else GONE
        pageIndicator.invalidate()
        invalidate()
    }

    private fun cancelGesture() {
        settleAnimator?.cancel()
        settleAnimator = null
        velocity?.recycle()
        velocity = null
        dragging = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun installTouchableStatusBarPassThrough() {
        runCatching {
            val listenerClass = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
            val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, arguments ->
                when (method.name) {
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                    "equals" -> return@newProxyInstance proxy === arguments?.firstOrNull()
                    "toString" -> return@newProxyInstance "NeXtepControlAreaInsets"
                }
                if (method.name == "onComputeInternalInsets") {
                    val info = arguments?.firstOrNull() ?: return@newProxyInstance null
                    info.javaClass.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
                        .invoke(info, TOUCHABLE_INSETS_REGION)
                    val region = info.javaClass.getField("touchableRegion").get(info) as Region
                    val status = dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP.toFloat()).toInt().coerceIn(0, height)
                    if (landscape) {
                        // Keep the existing physical-top status band mapping after subtree rotation.
                        if (controlOnLeft) region.set(status, 0, height, width)
                        else region.set(0, 0, height - status, width)
                    } else region.set(0, status, width, height)
                }
                null
            }
            val observer = viewTreeObserver
            observer.javaClass.getMethod("addOnComputeInternalInsetsListener", listenerClass).invoke(observer, listener)
            insetsObserver = observer
            insetsListener = listener
        }.onFailure { NeXtepLog.warn("control_video", "Unable to preserve status-bar touch pass-through", it) }
    }

    private fun removeTouchableStatusBarPassThrough() {
        val listener = insetsListener ?: return
        val observer = insetsObserver
        if (observer?.isAlive == true) runCatching {
            val listenerClass = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
            observer.javaClass.getMethod("removeOnComputeInternalInsetsListener", listenerClass).invoke(observer, listener)
        }
        insetsListener = null
        insetsObserver = null
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val TOUCHABLE_INSETS_REGION = 3
    }
}
