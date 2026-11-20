package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.AppLanguage
import kotlin.math.abs

internal class WorkspaceHeaderPager(
    context: Context,
    slotSnapshot: () -> Bundle,
    playerIcon: Drawable?,
) : FrameLayout(context) {
    private val monitor = WorkspaceMonitorView(context, slotSnapshot)
    private val monitorPage = FrameLayout(context).apply { addView(monitor, pageParams()) }
    private val game = WorkspaceJumpGameView(context, playerIcon)
    private val swipeThreshold = ViewConfiguration.get(context).scaledTouchSlop * 2f
    private var gameSelected = false
    private var workspaceVisible = false
    private var controlsSelected = true
    private var downX = 0f
    private var downY = 0f
    private var tracking = false

    init {
        addView(monitorPage, pageParams())
        addView(game, pageParams())
        renderPage(0f)
    }

    fun setItems(ids: List<String>) = monitor.setItems(ids)

    fun setIcons(icons: List<Drawable>) = game.setIcons(icons)

    fun setWallpaperLight(light: Boolean) {
        monitor.setWallpaperLight(light)
    }

    fun setWorkspaceVisible(visible: Boolean) {
        workspaceVisible = visible
        monitor.setWorkspaceVisible(visible)
        if (!visible && gameSelected) {
            gameSelected = false
            renderPage(0f)
        } else updateActivePages()
    }

    fun setControlsSelected(selected: Boolean) {
        if (controlsSelected == selected) return
        controlsSelected = selected
        updateActivePages()
    }

    private fun updateActivePages() {
        val active = workspaceVisible && controlsSelected
        monitor.setControlsSelected(active && !gameSelected)
        game.setPageActive(active && gameSelected)
    }

    private fun renderPage(direction: Float) {
        val shown: View = if (gameSelected) game else monitorPage
        val hidden: View = if (gameSelected) monitorPage else game
        hidden.animate().cancel()
        hidden.visibility = INVISIBLE
        shown.animate().cancel()
        shown.visibility = VISIBLE
        shown.translationY = -direction * height * 0.32f
        shown.alpha = if (direction == 0f) 1f else 0f
        if (direction != 0f) {
            shown.animate().alpha(1f).translationY(0f).setDuration(170L)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        isClickable = gameSelected
        updateActivePages()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!workspaceVisible || !controlsSelected) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tracking = false
            MotionEvent.ACTION_MOVE -> if (tracking && isVerticalSwipe(event)) {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP -> if (tracking) {
                tracking = false
                if (isVerticalSwipe(event)) {
                    gameSelected = !gameSelected
                    renderPage(if (event.y > downY) 1f else -1f)
                } else if (gameSelected && abs(event.x - downX) < swipeThreshold &&
                    abs(event.y - downY) < swipeThreshold) {
                    performClick()
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (!gameSelected) return false
        game.tap()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val scrollUp = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
        val scrollDown = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
        if (workspaceVisible && controlsSelected &&
            (action == scrollUp || action == scrollDown)
        ) {
            gameSelected = !gameSelected
            renderPage(if (action == scrollUp) -1f else 1f)
            announceForAccessibility(AppLanguage.string(context,
                if (gameSelected) R.string.workspace_game_title else R.string.workspace_monitor_title))
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onDetachedFromWindow() {
        monitorPage.animate().cancel()
        game.animate().cancel()
        game.setPageActive(false)
        super.onDetachedFromWindow()
    }

    private fun isVerticalSwipe(event: MotionEvent): Boolean {
        val dy = abs(event.y - downY)
        return dy >= swipeThreshold && dy > abs(event.x - downX) * 1.25f
    }

    private fun pageParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
}
