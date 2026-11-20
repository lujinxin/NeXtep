package io.github.lujinxin.nextep.systemui

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.doOnPreDraw
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.RecentTaskLockContract as LockContract
import io.github.lujinxin.nextep.workspace.SelectedRecentTask
import io.github.lujinxin.nextep.workspace.WorkspaceControlMetrics
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.roundToInt

/** Portrait recent-task cards with overlapping app icons and edge handoff to the page gesture. */
internal class RecentTasksView(
    context: Context,
    private val onTaskClicked: (SelectedRecentTask) -> Unit,
    private val onTaskDragStarting: (View, SelectedRecentTask) -> Boolean,
    private val onTaskDragTouch: (View, MotionEvent) -> Boolean,
    private val excludedTaskIds: () -> Set<Int>,
) : FrameLayout(context) {
    private data class Card(
        var entry: RecentTasksRepository.Entry,
        val tile: PortraitRecentTaskCard,
        var previewVersion: RecentTasksRepository.ThumbnailVersion? = null,
        var failedVersion: RecentTasksRepository.ThumbnailVersion? = null,
        var actionPending: Boolean = false,
    )
    private data class PreviewLoad(val version: RecentTasksRepository.ThumbnailVersion,
        val token: Any, val future: Future<*>)
    private class ClosingCard(val card: Card) {
        var animationFinished = false
        var result: Result<Unit>? = null
    }
    private val repository = RecentTasksRepository(context)
    private val ui = Handler(Looper.getMainLooper())
    private var worker = newWorker()
    private var pendingLoad: Future<*>? = null
    private val previews = mutableMapOf<SelectedRecentTask, PreviewLoad>()
    private val cards = mutableListOf<Card>()
    private var generation = 0
    private var pageActive = false
    private var workspaceVisible = false
    private var internalDragActive = false
    private var swipingCard: Card? = null
    private var closingCard: ClosingCard? = null
    private var clearRequest: Any? = null
    private val clearingTasks: Boolean get() = clearRequest != null
    private var layoutRevision = 0
    private var lockRequest = 0
    private var lockReceiverRegistered = false
    private var refreshDeferred = false
    private var lastRefreshAt = 0L
    private var hasLoaded = false
    private var refreshScheduled = false
    private var lastFailureAt = 0L
    private val refreshCallback = Runnable {
        refreshScheduled = false
        refresh()
    }
    private val observer = RecentTasksObserver(::onTasksChanged, ::onSnapshotChanged)
    private val updateLocks = Runnable { queryLockStates() }
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == LockContract.CHANGED && LockContract.isFrom(context, this, LockContract.LAUNCHER)) {
                scheduleLockQuery()
            }
        }
    }
    private var previewsScheduled = false
    private val previewCallback = Runnable {
        previewsScheduled = false
        loadVisiblePreviews()
    }
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(4), dp(10), 0)
    }
    private val scroll = object : HorizontalScrollView(context) {
        private var touchCard: Card? = null
        private var downX = 0f
        private var downY = 0f
        private var previousX = 0f
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                downX = event.x
                downY = event.y
                previousX = event.x
            }
            val handled = super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_MOVE && !internalDragActive) {
                val direction = if (event.x < previousX) 1 else -1
                if (abs(event.x - downX) > touchSlop && abs(event.x - downX) > abs(event.y - downY) &&
                    !canScrollHorizontally(direction)) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                previousX = event.x
            }
            return handled
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchCard = cards.firstOrNull { card ->
                        val bounds = Rect(0, 0, card.tile.width, card.tile.height)
                        offsetDescendantRectToMyCoords(card.tile, bounds)
                        bounds.contains((event.x + scrollX).toInt(), (event.y + scrollY).toInt())
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(event.x - downX)
                    val dy = abs(event.y - downY)
                    val card = touchCard
                    if (card != null && !card.actionPending && closingCard == null && canRefresh() && !internalDragActive &&
                        dy > touchSlop && dy > dx * 1.2f
                    ) {
                        swipingCard = card
                        resetCardMotion(card.tile)
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    if (dx > touchSlop && dx >= dy) touchCard = null
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touchCard = null
            }
            return super.onInterceptTouchEvent(event)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val card = swipingCard ?: return super.onTouchEvent(event)
            val distance = event.y - downY
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> card.tile.translationY = if (distance < 0f) {
                    (distance * 0.85f).coerceAtLeast(-card.tile.height.toFloat())
                } else {
                    (distance * 0.4f).coerceAtMost(dp(42).toFloat())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    val committed = event.actionMasked == MotionEvent.ACTION_UP &&
                        abs(distance) >= maxOf(dp(28), card.tile.height / 5)
                    finishCardSwipe()
                    if (committed && distance < 0 && closeCard(card)) return true
                    settleCard(card.tile)
                    if (committed && distance > 0) {
                        toggleCardLock(card)
                    }
                }
            }
            return true
        }
    }.apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        setOnScrollChangeListener { _, _, _, _, _ -> loadVisiblePreviews() }
    }
    private val message = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(Color.WHITE)
        setPadding(dp(20), 0, dp(20), 0)
        isClickable = true
        isFocusable = true
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        setOnClickListener { refresh(force = true) }
    }

    init {
        // Metadata can arrive before the first card layout. Load after children have real bounds.
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> loadVisiblePreviews() }
        val content = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP + 6)
            bottomMargin = dp(WorkspaceControlMetrics.APP_ICON_BOTTOM_INSET_DP)
        }
        addView(scroll, content)
        addView(message, LayoutParams(content))
        showMessage(R.string.control_tasks_loading)
    }

    fun setWorkspaceVisible(visible: Boolean) {
        if (workspaceVisible == visible) return
        workspaceVisible = visible
        if (visible) {
            if (isAttachedToWindow) observer.start()
            if (pageActive) startRefreshing()
        } else {
            clearRequest = null
            observer.stop()
            invalidateLoads(clearCards = true)
        }
    }

    fun setPageActive(active: Boolean) {
        if (pageActive == active) return
        pageActive = active
        if (active && workspaceVisible) startRefreshing()
        else if (!active) invalidateLoads(clearCards = false)
    }

    fun onTasksChanged() {
        // A missing system snapshot may become available after a task transition without its
        // lastActiveTime changing. Retry failures on real events, never on a timer or layout pass.
        cards.forEach { it.failedVersion = null }
        if (!canRefresh()) return
        if (isInternalDragActive() || closingCard != null || clearingTasks || pendingLoad != null) {
            refreshDeferred = true
            return
        }
        if (refreshScheduled) return
        refreshScheduled = true
        val delay = (lastRefreshAt + 500L - SystemClock.uptimeMillis()).coerceAtLeast(120L)
        postDelayed(refreshCallback, delay)
    }

    private fun onSnapshotChanged(taskId: Int, invalidated: Boolean) {
        val affected = cards.filter { it.entry.task.taskId == taskId }
        if (affected.isEmpty()) return
        repository.onSnapshotChanged(taskId, invalidated)
        affected.forEach { card ->
            previews.remove(card.entry.task)?.future?.cancel(true)
            card.failedVersion = null
            if (invalidated) {
                card.tile.setSnapshot(null)
                card.previewVersion = null
            }
        }
        if (canRefresh() && !internalDragActive && !previewsScheduled) {
            previewsScheduled = true
            postDelayed(previewCallback, 120L)
        }
    }

    private fun canRefresh() = pageActive && workspaceVisible && isAttachedToWindow && !worker.isShutdown

    fun canStartPageGesture() = canRefresh() && !internalDragActive

    fun clearBackgroundTasks() {
        if (!canStartPageGesture() || clearingTasks || cards.any { it.actionPending }) return
        val request = Any()
        clearRequest = request
        val excluded = excludedTaskIds().toSet()
        worker.submit {
            val result = runCatching { repository.backgroundTaskTargets(excluded) }
            ui.post {
                if (clearRequest !== request) return@post
                result.onSuccess { targets ->
                    clearBatch(request, targets.chunked(LockContract.MAX_TARGETS).iterator(), mutableSetOf())
                }.onFailure {
                    NeXtepLog.warn("recent_tasks", "Unable to list background tasks to clear", it)
                    finishClear(request, emptySet(), failed = true)
                }
            }
        }
    }

    private fun clearBatch(
        request: Any,
        batches: Iterator<List<SelectedRecentTask>>,
        removedTasks: MutableSet<SelectedRecentTask>,
    ) {
        if (!batches.hasNext()) {
            finishClear(request, removedTasks, failed = false)
            return
        }
        val batch = batches.next()
        LockContract.request(context, LockContract.CLEAR, batch) { removed ->
            if (clearRequest !== request) return@request
            if (removed == null) {
                finishClear(request, removedTasks, failed = true)
            } else {
                batch.forEachIndexed { index, task -> if (removed[index]) removedTasks.add(task) }
                clearBatch(request, batches, removedTasks)
            }
        }
    }

    private fun finishClear(request: Any, removedTasks: Set<SelectedRecentTask>, failed: Boolean) {
        fun complete() {
            if (clearRequest !== request) return
            clearRequest = null
            if (canRefresh() && removedTasks.isNotEmpty()) {
                render(cards.map { it.entry }.filterNot { it.task in removedTasks }, animateChanges = true)
            }
            if (failed && workspaceVisible && isAttachedToWindow) showActionError(R.string.control_tasks_clear_failed)
            refreshDeferred = false
            onTasksChanged()
        }
        if (!canRefresh() || !ValueAnimator.areAnimatorsEnabled()) {
            complete()
            return
        }
        val visible = Rect()
        val exiting = cards.filter { it.entry.task in removedTasks && it.tile.getLocalVisibleRect(visible) }
        if (exiting.isEmpty()) {
            complete()
            return
        }
        val duration = 180L
        val stagger = 18L
        exiting.forEachIndexed { index, card ->
            val tile = card.tile
            tile.animate().cancel()
            tile.animate().translationY(-tile.height.toFloat() - dp(12)).alpha(0f)
                .setStartDelay(index * stagger).setDuration(duration)
                .setInterpolator(AccelerateInterpolator()).start()
        }
        ui.postDelayed(::complete, duration + exiting.lastIndex * stagger)
    }

    private fun startRefreshing() {
        if (!canRefresh()) return
        cards.filterNot { it.tile.hasSnapshot }.forEach { it.failedVersion = null }
        refresh(force = true)
    }

    fun isInternalDragActive() = internalDragActive || swipingCard != null

    fun finishInternalDrag() {
        val wasDragging = internalDragActive
        internalDragActive = false
        parent?.requestDisallowInterceptTouchEvent(false)
        if (refreshDeferred || wasDragging) {
            refreshDeferred = false
            refresh(force = true)
        }
        loadVisiblePreviews()
    }

    fun canScrollHorizontallyAt(x: Float, y: Float, direction: Int): Boolean {
        val bounds = Rect(scroll.scrollX, scroll.scrollY,
            scroll.scrollX + scroll.width, scroll.scrollY + scroll.height)
        offsetDescendantRectToMyCoords(scroll, bounds)
        return bounds.contains(x.toInt(), y.toInt()) && scroll.canScrollHorizontally(direction)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lockReceiverRegistered = runCatching {
            context.registerReceiver(lockReceiver, IntentFilter(LockContract.CHANGED), LockContract.PERMISSION,
                ui, Context.RECEIVER_EXPORTED)
        }.onFailure { NeXtepLog.warn("recent_task_lock", "Unable to observe native lock changes", it) }.isSuccess
        if (worker.isShutdown) worker = newWorker()
        if (workspaceVisible) observer.start()
        if (pageActive && workspaceVisible) startRefreshing()
    }

    override fun onDetachedFromWindow() {
        clearRequest = null
        if (lockReceiverRegistered) {
            context.unregisterReceiver(lockReceiver)
            lockReceiverRegistered = false
        }
        observer.stop()
        invalidateLoads(clearCards = true)
        worker.shutdownNow()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { loadVisiblePreviews() }
    }

    private fun refresh(force: Boolean = false) {
        if (!canRefresh()) return
        if (isInternalDragActive() || closingCard != null || clearingTasks) { refreshDeferred = true; return }
        if (pendingLoad != null) { refreshDeferred = true; return }
        if (!force && SystemClock.uptimeMillis() - lastRefreshAt < 450L) { onTasksChanged(); return }
        val request = generation
        val excluded = excludedTaskIds().toSet()
        lastRefreshAt = SystemClock.uptimeMillis()
        // Keep the last valid list visible during refresh, including page re-entry.
        if (!hasLoaded) showMessage(R.string.control_tasks_loading)
        pendingLoad = worker.submit {
            val result = runCatching { repository.load(excluded) }
            ui.post {
                if (!accepts(request)) return@post
                pendingLoad = null
                if (isInternalDragActive() || closingCard != null || clearingTasks) { refreshDeferred = true; return@post }
                result.onSuccess { entries ->
                    render(entries, animateChanges = hasLoaded)
                    hasLoaded = true
                }.onFailure {
                    if (lastFailureAt == 0L || SystemClock.uptimeMillis() - lastFailureAt > 30_000L) {
                        lastFailureAt = SystemClock.uptimeMillis()
                        NeXtepLog.warn("recent_tasks", "Unable to load recent tasks", it)
                    }
                    if (!hasLoaded) showMessage(R.string.control_tasks_load_failed)
                }
                if (refreshDeferred) { refreshDeferred = false; onTasksChanged() }
            }
        }
    }

    private fun render(entries: List<RecentTasksRepository.Entry>, animateChanges: Boolean) {
        val revision = ++layoutRevision
        val position = scroll.scrollX
        val animate = animateChanges && ValueAnimator.areAnimatorsEnabled() &&
            (cards.size != entries.size || cards.indices.any { cards[it].entry.task != entries[it].task })
        val previousPositions = if (animate) cards.associate { card ->
            card.tile to card.tile.left + card.tile.translationX - position
        } else emptyMap()
        val anchor = cards.firstOrNull { it.tile.right > position }
        val anchorOffset = anchor?.let { it.tile.left - position } ?: 0
        val existing = cards.associateBy { it.entry.task }
        val updated = entries.map { entry ->
            (existing[entry.task] ?: createCard(entry)).also { card ->
                card.entry = entry
                card.tile.contentDescription = AppLanguage.string(context, R.string.control_task_open, entry.label)
            }
        }
        val retained = entries.mapTo(mutableSetOf()) { it.task }
        cards.filter { it.entry.task !in retained }.forEach { card ->
            previews.remove(card.entry.task)?.future?.cancel(true)
            resetCardMotion(card.tile)
            row.removeView(card.tile)
            card.tile.setSnapshot(null)
        }
        updated.forEachIndexed { index, card ->
            if (row.getChildAt(index) !== card.tile) {
                if (card.tile.parent === row) row.removeView(card.tile)
                row.addView(card.tile, index, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
                    .apply {
                        marginStart = dp(6)
                        marginEnd = dp(6)
                    })
            }
        }
        cards.clear()
        cards.addAll(updated)
        scheduleLockQuery()
        if (entries.isEmpty()) {
            showMessage(R.string.control_tasks_empty)
            return
        }
        message.visibility = GONE
        scroll.visibility = VISIBLE
        scroll.doOnPreDraw {
            if (!canRefresh() || revision != layoutRevision) return@doOnPreDraw
            val retainedAnchor = anchor?.let { old -> cards.firstOrNull { it.entry.task == old.entry.task } }
            val restored = if (position > 0 && retainedAnchor != null) retainedAnchor.tile.left - anchorOffset else position
            scroll.scrollTo(restored, 0)
            loadVisiblePreviews()
            if (animate && !isInternalDragActive() && closingCard == null) animateCardLayout(previousPositions)
        }
        scroll.postInvalidateOnAnimation()
    }

    private fun animateCardLayout(previousPositions: Map<PortraitRecentTaskCard, Float>) {
        val visible = Rect()
        for (card in cards) {
            val tile = card.tile
            if (!tile.getLocalVisibleRect(visible)) continue
            val previousX = previousPositions[tile]
            if (previousX == null) {
                tile.alpha = 0.25f
                tile.scaleX = 0.96f
                tile.scaleY = 0.96f
                tile.translationY = dp(14).toFloat()
                settleCard(tile)
            } else {
                val offset = previousX - (tile.left - scroll.scrollX)
                if (abs(offset) < 1f) continue
                tile.translationX = offset
                tile.animate().translationX(0f).setDuration(220L)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }
    }

    private fun createCard(entry: RecentTasksRepository.Entry): Card {
        val tile = PortraitRecentTaskCard(context, entry.icon)
        val card = Card(entry, tile)
        tile.apply {
            isClickable = true
            isLongClickable = true
            isFocusable = true
            contentDescription = AppLanguage.string(context, R.string.control_task_open, entry.label)
            setOnClickListener {
                if (!internalDragActive && !card.actionPending && closingCard == null && !clearingTasks) {
                    onTaskClicked(card.entry.task)
                    refresh(force = true)
                }
            }
            setOnLongClickListener { source ->
                if (card.actionPending || closingCard != null || clearingTasks) return@setOnLongClickListener false
                resetCardMotion(tile)
                val started = onTaskDragStarting(source, card.entry.task)
                internalDragActive = started
                if (started) parent?.requestDisallowInterceptTouchEvent(true)
                started
            }
            setOnTouchListener { source, event -> onTaskDragTouch(source, event) }
            accessibilityDelegate = object : AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                        R.id.control_task_toggle_lock,
                        AppLanguage.string(context, if (tile.isLocked) R.string.control_task_unlock else R.string.control_task_lock),
                    ))
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    when (action) {
                        AccessibilityNodeInfo.ACTION_DISMISS -> closeCard(card)
                        R.id.control_task_toggle_lock -> toggleCardLock(card)
                        else -> return super.performAccessibilityAction(host, action, args)
                    }
                    return true
                }
            }
        }
        return card
    }

    private fun loadVisiblePreviews() {
        if (!canRefresh() || isInternalDragActive()) return
        val request = generation
        val visible = Rect()
        cards.forEach { card ->
            val entry = card.entry
            val task = entry.task
            if (!card.tile.getLocalVisibleRect(visible) || card.tile.width == 0) {
                // Keep memory proportional to visible cards rather than the entire recent-task list.
                previews.remove(task)?.future?.cancel(true)
                card.tile.setSnapshot(null)
                card.previewVersion = null
                return@forEach
            }
            val version = repository.thumbnailVersion(entry)
            repository.cachedThumbnail(entry)?.let { cached ->
                if (!card.tile.hasSnapshot || card.previewVersion != cached.version) {
                    card.tile.setSnapshot(cached.bitmap)
                    card.previewVersion = cached.version
                }
            }
            if (card.previewVersion == version) return@forEach
            val pending = previews[task]
            if (pending?.version == version || card.failedVersion == version) return@forEach
            previews.remove(task)?.future?.cancel(true)
            val token = Any()
            val future = worker.submit {
                val thumbnail = repository.thumbnail(entry, version)
                ui.post {
                    if (!accepts(request) || cards.none { it === card } || previews[task]?.token !== token) return@post
                    previews.remove(task)
                    card.failedVersion = if (thumbnail?.version == version) null else version
                    if (thumbnail != null && repository.thumbnailVersion(card.entry) == version &&
                        card.tile.getLocalVisibleRect(Rect())) {
                        card.tile.setSnapshot(thumbnail.bitmap)
                        card.previewVersion = thumbnail.version
                    }
                    // Images belong to the bounded repository cache; never recycle a cached bitmap here.
                }
            }
            previews[task] = PreviewLoad(version, token, future)
        }
    }

    private fun accepts(request: Int) = request == generation && pageActive && workspaceVisible && isAttachedToWindow

    private fun finishCardSwipe() {
        if (swipingCard == null) return
        swipingCard = null
        scroll.parent?.requestDisallowInterceptTouchEvent(false)
        if (refreshDeferred) {
            refreshDeferred = false
            onTasksChanged()
        }
        loadVisiblePreviews()
    }

    private fun settleCard(tile: PortraitRecentTaskCard) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            resetCardMotion(tile)
            return
        }
        tile.animate().translationY(0f).alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(220L).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun resetCardMotion(tile: PortraitRecentTaskCard) {
        tile.animate().cancel()
        tile.animate().setStartDelay(0)
        tile.translationX = 0f
        tile.translationY = 0f
        tile.alpha = 1f
        tile.scaleX = 1f
        tile.scaleY = 1f
    }

    private fun scheduleLockQuery() {
        if (!canRefresh()) return
        removeCallbacks(updateLocks)
        postDelayed(updateLocks, 100L)
    }

    private fun queryLockStates() {
        if (!canRefresh() || cards.isEmpty()) return
        val request = ++lockRequest
        val targetCards = cards.toList()
        val targets = targetCards.map { it.entry.task }
        LockContract.request(context, LockContract.QUERY, targets) { states ->
            if (states == null || request != lockRequest || !canRefresh()) return@request
            targetCards.forEachIndexed { index, card ->
                if (cards.any { it === card } && card.entry.task == targets[index]) card.tile.isLocked = states[index]
            }
        }
    }

    private fun closeCard(card: Card): Boolean {
        if (!canActOn(card)) return false
        val request = generation
        val task = card.entry.task
        val closing = ClosingCard(card)
        card.actionPending = true
        closingCard = closing
        val tile = card.tile
        tile.animate().cancel()
        if (ValueAnimator.areAnimatorsEnabled() && tile.isLaidOut) {
            tile.animate().translationY(-tile.height.toFloat() - dp(12)).alpha(0f)
                .setDuration(180L).setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    closing.animationFinished = true
                    finishClosingCard(closing)
                }.start()
        } else {
            closing.animationFinished = true
        }
        worker.submit {
            val result = repository.closeTask(task)
            ui.post {
                if (!accepts(request)) return@post
                closing.result = result
                finishClosingCard(closing)
            }
        }
        return true
    }

    private fun finishClosingCard(closing: ClosingCard) {
        if (closingCard !== closing || !closing.animationFinished) return
        val result = closing.result ?: return
        closingCard = null
        closing.card.actionPending = false
        result.onSuccess {
            render(cards.filterNot { it === closing.card }.map { it.entry }, animateChanges = true)
        }.onFailure {
            settleCard(closing.card.tile)
            NeXtepLog.warn("recent_tasks", "Unable to close recent task", it)
            showActionError(R.string.control_task_close_failed)
        }
        refreshDeferred = false
        onTasksChanged()
    }

    private fun toggleCardLock(card: Card) {
        if (!canActOn(card)) return
        val request = generation
        val task = card.entry.task
        lockRequest++
        card.actionPending = true
        LockContract.request(context, LockContract.TOGGLE, listOf(task)) { states ->
            card.actionPending = false
            if (!accepts(request)) return@request
            if (states == null) showActionError(R.string.control_task_lock_failed)
            else if (cards.any { it === card }) card.tile.isLocked = states.single()
            scheduleLockQuery()
        }
    }

    private fun canActOn(card: Card): Boolean = canRefresh() && !isInternalDragActive() && closingCard == null && !clearingTasks && !card.actionPending &&
        cards.any { it === card } && card.entry.task.taskId !in excludedTaskIds()

    private fun showActionError(messageId: Int) {
        Toast.makeText(context, AppLanguage.string(context, messageId), Toast.LENGTH_SHORT).show()
    }

    private fun invalidateLoads(clearCards: Boolean) {
        generation++
        layoutRevision++
        lockRequest++
        removeCallbacks(updateLocks)
        swipingCard = null
        closingCard?.card?.actionPending = false
        closingCard = null
        cards.forEach { resetCardMotion(it.tile) }
        removeCallbacks(refreshCallback)
        removeCallbacks(previewCallback)
        refreshScheduled = false
        previewsScheduled = false
        refreshDeferred = false
        pendingLoad?.cancel(true)
        pendingLoad = null
        previews.forEach { (task, pending) ->
            pending.future.cancel(true)
            cards.firstOrNull { it.entry.task == task }?.failedVersion = null
        }
        previews.clear()
        if (clearCards) {
            cards.forEach { it.tile.setSnapshot(null) }
            cards.clear()
            row.removeAllViews()
            hasLoaded = false
            repository.clearThumbnails()
        }
    }

    private fun showMessage(resource: Int) {
        scroll.visibility = GONE
        message.text = AppLanguage.string(context, resource)
        message.visibility = VISIBLE
    }

    private fun newWorker() = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NeXtep-RecentTasks").apply { isDaemon = true }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** Fits the full snapshot at its own aspect ratio with the icon overlapping its lower edge. */
private class PortraitRecentTaskCard(context: Context, icon: Drawable) : FrameLayout(context) {
    private var snapshotAspect = resources.displayMetrics.run {
        minOf(widthPixels, heightPixels).coerceAtLeast(1).toFloat() / maxOf(widthPixels, heightPixels, 1)
    }
    private val preview = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = GradientDrawable().apply {
            setColor(Color.rgb(43, 48, 56))
            cornerRadius = dp(8).toFloat()
        }
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val appIcon = ImageView(context).apply {
        setImageDrawable(icon.constantState?.newDrawable()?.mutate() ?: icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val lockBadge = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init {
            visibility = INVISIBLE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.scale(width / 16f, height / 16f)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(26, 43, 61)
            canvas.drawCircle(8f, 8f, 8f, paint)
            paint.color = Color.WHITE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.3f
            canvas.drawArc(5.8f, 3.5f, 10.2f, 9f, 180f, 180f, false, paint)
            canvas.drawRoundRect(4.6f, 7.1f, 11.4f, 12.2f, 1.2f, 1.2f, paint)
            canvas.restore()
        }
    }
    var isLocked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            lockBadge.visibility = if (value) VISIBLE else INVISIBLE
            stateDescription = if (value) AppLanguage.string(context, R.string.control_task_locked) else null
        }

    init {
        addView(preview)
        addView(appIcon)
        addView(lockBadge)
    }

    val hasSnapshot: Boolean
        get() = preview.drawable != null

    fun setSnapshot(bitmap: Bitmap?) {
        if (bitmap == null) {
            // Keep the card width as its image is released offscreen.
            if (hasSnapshot) preview.setImageDrawable(null)
            return
        }
        val aspect = bitmap.width.toFloat() / bitmap.height
        preview.setImageBitmap(bitmap)
        if (snapshotAspect != aspect) {
            snapshotAspect = aspect
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) dp(144)
            else MeasureSpec.getSize(heightMeasureSpec)
        val preferredIconSize = dp(32).coerceAtMost(height)
        val maxSnapshotHeight = (height - preferredIconSize / 2).coerceAtLeast(1)
        val preferredSnapshotWidth = (maxSnapshotHeight * snapshotAspect).roundToInt().coerceAtLeast(1)
        val width = resolveSize(maxOf(preferredSnapshotWidth, preferredIconSize), widthMeasureSpec)
        val iconSize = minOf(width, preferredIconSize)
        val snapshotWidth = minOf(width, preferredSnapshotWidth)
        val snapshotHeight = if (snapshotWidth < preferredSnapshotWidth) {
            (snapshotWidth / snapshotAspect).roundToInt().coerceIn(0, maxSnapshotHeight)
        } else maxSnapshotHeight
        preview.measure(MeasureSpec.makeMeasureSpec(snapshotWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(snapshotHeight, MeasureSpec.EXACTLY))
        appIcon.measure(MeasureSpec.makeMeasureSpec(iconSize, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(iconSize, MeasureSpec.EXACTLY))
        val badgeSpec = MeasureSpec.makeMeasureSpec(minOf(dp(15), iconSize), MeasureSpec.EXACTLY)
        lockBadge.measure(badgeSpec, badgeSpec)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val previewLeft = (width - preview.measuredWidth) / 2
        val previewBottom = height - appIcon.measuredHeight / 2
        val previewTop = previewBottom - preview.measuredHeight
        preview.layout(previewLeft, previewTop, previewLeft + preview.measuredWidth, previewBottom)
        val iconLeft = (width - appIcon.measuredWidth) / 2
        val iconTop = height - appIcon.measuredHeight
        appIcon.layout(iconLeft, iconTop, iconLeft + appIcon.measuredWidth, height)
        val badgeRight = minOf(width, appIcon.right + dp(3))
        val badgeTop = (iconTop - dp(3)).coerceAtLeast(0)
        lockBadge.layout(badgeRight - lockBadge.measuredWidth, badgeTop,
            badgeRight, badgeTop + lockBadge.measuredHeight)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
