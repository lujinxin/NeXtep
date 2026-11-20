package io.github.lujinxin.nextep.systemui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Region
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.ControlAreaVideoState
import io.github.lujinxin.nextep.workspace.SelectedRecentTask
import io.github.lujinxin.nextep.workspace.WorkspaceControlMetrics
import kotlin.math.abs
import kotlin.math.roundToInt

/** Retained task, function and video pages, measured and dragged in panel-local axes. */
internal class ControlAreaPager(
    context: Context,
    private val functions: TopAppStripView,
    onTaskClicked: (SelectedRecentTask) -> Unit,
    onTaskDragStarting: (View, SelectedRecentTask) -> Boolean,
    onTaskDragTouch: (View, MotionEvent) -> Boolean,
    excludedTaskIds: () -> Set<Int>,
    private val onFloatingPipBoundsChanged: (Rect?) -> Unit,
) : FrameLayout(context), WorkspacePanelRotationContent {
    private val tasks = RecentTasksView(context, onTaskClicked, onTaskDragStarting,
        onTaskDragTouch, excludedTaskIds)
    private val video = ControlAreaVideoView(
        context,
        ::onVideoStateChanged,
        onReleaseRequested = { showPage(0) },
        canAutomaticallyCapture = {
            activePointerId == MotionEvent.INVALID_POINTER_ID && !consumesTaskGesture && !isInternalDragActive() &&
                settleAnimator?.isRunning != true
        },
    )
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minimumVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 2
    private val maximumVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private val tabLabels = listOf(R.string.control_page_tasks, R.string.control_page_functions, R.string.control_page_video)
    private val boundaryDivider = View(context).apply {
        setBackgroundColor(Color.argb(90, 255, 255, 255))
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
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
    private var taskGesture: TaskPageGesture? = null
    private var consumesTaskGesture = false
    private var landscape = false
    private var controlOnLeft = false
    private val touchRegion = WorkspaceTouchRegion(this, ::fillTouchRegion)

    init {
        clipChildren = true
        clipToPadding = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        functions.setOnVideoActionRequested {
            if (embedded) {
                video.releaseVideo()
            } else {
                showPage(1)
                if (video.canCaptureVideo()) video.captureVideo()
            }
        }
        functions.setOnControlsChanged { updatePageLabels() }
        fun pageParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(video, pageParams())
        // Keep ordinary controls after the SurfaceView hole during transparent-region gathering.
        addView(functions, pageParams())
        addView(tasks, pageParams())
        // Keep the workspace boundary fixed as the pages slide.
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
        tasks.setWorkspaceVisible(visible && !locked)
        if (!visible || locked) {
            cancelGesture()
            showPage(0, animate = false)
        }
        video.setWorkspaceState(visible, locked)
    }

    fun refreshHost() = video.refreshHost()

    fun openDockedPip(taskId: Int) = video.openDockedPip(taskId)

    fun onTasksChanged() = tasks.onTasksChanged()

    fun finishInternalTaskDrag() = tasks.finishInternalDrag()

    private fun isInternalDragActive() = functions.isInternalAppDragActive() || tasks.isInternalDragActive()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        video.setNativeInputAvailable(touchRegion.install())
        post { SystemUiStatusBarGestureInstaller.install(this) }
    }

    override fun onDetachedFromWindow() {
        cancelGesture()
        video.setNativeInputAvailable(false)
        touchRegion.remove()
        WorkspacePipMotionHook.layer = null
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
        if (dispatchTaskGesture(event)) return true
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

    private fun dispatchTaskGesture(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                taskGesture = null
                consumesTaskGesture = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (consumesTaskGesture) {
                    taskGesture = null
                } else if (event.pointerCount == 2 && selectedPage == -1 && tasks.canStartPageGesture()) {
                    val cancel = MotionEvent.obtain(event)
                    try {
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                    } finally {
                        cancel.recycle()
                    }
                    cancelGesture()
                    consumesTaskGesture = true
                    taskGesture = TaskPageGesture(GestureFinger(event, 0), GestureFinger(event, 1))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val gesture = taskGesture
                taskGesture = null
                if (gesture != null && selectedPage == -1 && tasks.canStartPageGesture()) {
                    val distance = maxOf(dp(40f), touchSlop * 2f)
                    when {
                        gesture.matches(event, distance, horizontal = true) -> showPage(0)
                        gesture.matches(event, distance, horizontal = false) -> tasks.clearBackgroundTasks()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                taskGesture = null
                val consumed = consumesTaskGesture
                consumesTaskGesture = false
                return consumed
            }
        }
        return consumesTaskGesture
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (isInternalDragActive()) return false
        if (dragging) return true
        return tryStartPageDrag(event)
    }

    private fun tryStartPageDrag(event: MotionEvent): Boolean {
        if (isInternalDragActive()) return false
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
        if (selectedPage == 0 && functions.canScrollHorizontallyAt(downX - functions.translationX, downY, direction)) return false
        if (selectedPage == -1 && tasks.canScrollHorizontallyAt(downX - tasks.translationX, downY, direction)) return false
        if ((pageOffset <= -width && dx > 0f) || (pageOffset >= width && dx < 0f)) return false
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
        val index = event.findPointerIndex(activePointerId)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                // Empty video/background areas have no child touch target; in that case
                // ViewGroup routes moves directly here without onInterceptTouchEvent().
                if (!dragging) tryStartPageDrag(event)
                if (dragging && index >= 0) {
                    pageOffset = (gestureOffset - (event.getX(index) - downX))
                        .coerceIn(-width.toFloat(), width.toFloat())
                    updatePagePositions()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (index < 0) return true
                velocity?.computeCurrentVelocity(1000, maximumVelocity.toFloat())
                val vx = velocity?.getXVelocity(activePointerId) ?: 0f
                val target = if (dragging && abs(vx) > minimumVelocity) {
                    selectedPage + if (vx < 0f) 1 else -1
                } else if (width > 0) (pageOffset / width).roundToInt() else selectedPage
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
        // SurfaceView hole cull the functions page or indicator after a layout.
        region?.setEmpty()
        return false
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = true
        if (selectedPage < 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        if (selectedPage > -1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (!isInternalDragActive() && action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD && selectedPage < 1) {
            showPage(selectedPage + 1)
            return true
        }
        if (!isInternalDragActive() && action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD && selectedPage > -1) {
            showPage(selectedPage - 1)
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private fun onVideoStateChanged(state: ControlAreaVideoState) {
        onFloatingPipBoundsChanged(state.floatingPipBounds)
        WorkspacePipMotionHook.layer = state.floatingPipLayer
        val wasEmbedded = embedded
        embedded = state.embedded
        functions.setVideoActionState(canCapture = video.canCaptureVideo(), embedded = state.embedded)
        if (embedded && !wasEmbedded && selectedPage != 1) {
            showPage(1)
        } else if (!embedded && wasEmbedded && selectedPage == 1) {
            cancelGesture()
            showPage(0)
        }
        updatePagePositions()
    }

    private fun showPage(page: Int, animate: Boolean = true) {
        selectedPage = page.coerceIn(-1, 1)
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
        tasks.translationX = -width - pageOffset
        // Never use GONE/INVISIBLE here: SurfaceView must retain the remote source.
        video.translationX = width - pageOffset
        // The capsule floats over the picture, so the video can use the full panel height.
        video.setPageViewport(video.translationX, width, height)
        val videoSelected = selectedPage == 1
        val tasksSelected = selectedPage == -1
        functions.setMonitorControlsSelected(selectedPage == 0)
        tasks.setPageActive(tasksSelected && abs(pageOffset + width.toFloat()) < 1f)
        video.setPageActive(videoSelected && abs(pageOffset - width.toFloat()) < 1f)
        functions.importantForAccessibility = if (selectedPage == 0) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        tasks.importantForAccessibility = if (tasksSelected) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        video.importantForAccessibility = if (videoSelected) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        updatePageLabels()
        invalidate()
    }

    private fun updatePageLabels() {
        contentDescription = AppLanguage.string(context, tabLabels[selectedPage + 1])
    }

    private fun cancelGesture() {
        taskGesture = null
        settleAnimator?.cancel()
        settleAnimator = null
        velocity?.recycle()
        velocity = null
        dragging = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun fillTouchRegion(region: Region) {
        val status = dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP.toFloat()).toInt().coerceIn(0, height)
        if (landscape) {
            // Keep the existing physical-top status band mapping after subtree rotation.
            if (controlOnLeft) region.set(status, 0, height, width)
            else region.set(0, 0, height - status, width)
        } else region.set(0, status, width, height)
        (rootView as? WorkspacePanelHost)?.subtractFloatingPip(region)
        runCatching { video.subtractNativeInputRegion(region) }.onFailure {
            video.setNativeInputAvailable(false)
            NeXtepLog.warn("control_video", "Native player touch region unavailable", it)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private data class TaskPageGesture(val first: GestureFinger, val second: GestureFinger) {
        fun matches(event: MotionEvent, distance: Float, horizontal: Boolean) =
            first.matches(event, distance, horizontal) && second.matches(event, distance, horizontal)
    }

    private class GestureFinger(event: MotionEvent, index: Int) {
        private val id = event.getPointerId(index)
        private val x = event.getX(index)
        private val y = event.getY(index)

        fun matches(event: MotionEvent, distance: Float, horizontal: Boolean): Boolean {
            val index = event.findPointerIndex(id)
            if (index < 0) return false
            val dx = event.getX(index) - x
            val dy = event.getY(index) - y
            val travel = if (horizontal) -dx else -dy
            val crossAxis = if (horizontal) dy else dx
            return travel >= distance && travel > abs(crossAxis) * 1.2f
        }
    }

}
