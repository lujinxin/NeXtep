package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import io.github.lujinxin.nextep.display.VirtualDisplaySlot
import io.github.lujinxin.nextep.display.SlotTaskCoordinator
import io.github.lujinxin.nextep.display.WORKSPACE_SLOT_COUNT
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.safety.FeatureGate
import io.github.lujinxin.nextep.task.MainTaskPresentationCoordinator
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry
import io.github.lujinxin.nextep.workspace.SidebarSide
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge

class NeXtepWindowController(
    private val context: Context,
    private val mainTaskPresenter: MainTaskPresentationCoordinator,
    private val onSidebarSideRequested: (SidebarSide) -> Unit,
    private val onSettingsRequested: () -> Unit,
    private val onExitRequested: () -> Unit,
) {
    private data class WindowRecord(val view: View, val title: String)

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val records = mutableListOf<WindowRecord>()
    private var initialized = false
    private var slotCoordinator: SlotTaskCoordinator? = null
    private var topAppStrip: TopAppStripView? = null
    private var sidebarView: FrameLayout? = null
    private var slotWindows: List<VirtualDisplaySlot> = emptyList()
    private var wallpaperBitmap: android.graphics.Bitmap? = null
    private var sidebarSide = SystemServerWorkspaceBridge.sidebarSide(context)

    fun show(): WorkspaceGeometry {
        initializeIfNeeded()
        check(records.size == EXPECTED_WINDOW_COUNT) { "SystemUI windows are unavailable" }
        reconfigure().getOrThrow()
        records.forEach { record ->
            val view = record.view
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.translationX = 0f
            view.alpha = 0f
            view.translationY = when (record.title) {
                "NeXtepTopBar" -> -dp(ENTER_OFFSET_DP)
                else -> dp(ENTER_OFFSET_DP)
            }
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(WORKSPACE_ENTER_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .start()
        }
        refreshWallpaperBackdrop()
        topAppStrip?.setWorkspaceVisible(true)
        topAppStrip?.refresh()
        if (FeatureGate.VIRTUAL_DISPLAY_SLOTS.defaultEnabled) {
            slotCoordinator?.activate()
        }
        return geometry()
    }

    fun hide() {
        slotCoordinator?.deactivate()
        topAppStrip?.setWorkspaceVisible(false)
        records.forEach { record ->
            val view = record.view
            view.animate().cancel()
            view.animate()
                .alpha(0f)
                .translationY(
                    if (record.title == "NeXtepTopBar") -dp(EXIT_OFFSET_DP)
                    else dp(EXIT_OFFSET_DP),
                )
                .setDuration(WORKSPACE_EXIT_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .withEndAction {
                    view.visibility = View.GONE
                    view.translationX = 0f
                    view.translationY = 0f
                }
                .start()
        }
    }

    fun launchInSlot(intent: Intent): Result<Unit> {
        if (!FeatureGate.VIRTUAL_DISPLAY_SLOTS.defaultEnabled) {
            return Result.failure(IllegalStateException("VirtualDisplay slot feature disabled"))
        }
        return slotCoordinator?.launchInSlot(intent)
            ?: Result.failure(IllegalStateException("VirtualDisplay slot unavailable"))
    }

    fun openFromLauncher(intent: Intent): Result<Unit> = slotCoordinator
        ?.openFromLauncher(intent)
        ?: Result.failure(IllegalStateException("Slot coordinator unavailable"))

    fun currentGeometry(): WorkspaceGeometry = geometry()

    fun reconfigure(): Result<WorkspaceGeometry> = runCatching {
        initializeIfNeeded()
        check(records.size == EXPECTED_WINDOW_COUNT) { "SystemUI windows are unavailable" }
        val geometry = geometry()
        applyWindowGeometry(geometry)
        sidebarView?.let { applySlotLayout(it, geometry) }
        refreshWallpaperBackdrop()
        NeXtepLog.info("workspace_configuration", "Reconfigured geometry=$geometry")
        geometry
    }

    fun setSidebarSide(side: SidebarSide): Result<WorkspaceGeometry> = runCatching {
        initializeIfNeeded()
        check(records.size == EXPECTED_WINDOW_COUNT) { "SystemUI windows are unavailable" }
        sidebarSide = side
        val geometry = geometry()
        applyWindowGeometry(geometry)
        topAppStrip?.setSidebarSide(side)
        sidebarView?.let { panel -> applySlotLayout(panel, geometry) }
        applySidebarBackdrop(geometry)
        records.firstOrNull { it.title == "NeXtepSidebar" }?.view?.let { sidebar ->
            sidebar.animate().cancel()
            sidebar.translationY = 0f
            sidebar.alpha = 0f
            sidebar.translationX = if (side == SidebarSide.LEFT) {
                -dp(SIDEBAR_ENTER_OFFSET_DP)
            } else {
                dp(SIDEBAR_ENTER_OFFSET_DP)
            }
            sidebar.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(SIDEBAR_SWITCH_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .start()
        }
        NeXtepLog.info("sidebar_side", "Applied side=$side geometry=$geometry")
        geometry
    }

    fun slotStatesDescription(): String = slotCoordinator
        ?.slotStates()
        ?.mapIndexed { index, state -> "${index + 1}:$state" }
        ?.joinToString(" | ")
        ?: "unavailable"

    private fun initializeIfNeeded() {
        if (initialized) return
        initialized = true
        val windowType = SystemUiWindowTypeResolver.resolve() ?: run {
            initialized = false
            return
        }
        val geometry = geometry()

        addWindow(
            title = "NeXtepTopBar",
            view = TopAppStripView(
                context = context,
                initialSidebarSide = sidebarSide,
                onAppClicked = { intent ->
                    slotCoordinator?.openInMain(intent)?.onFailure { error ->
                        NeXtepLog.warn("top_apps", "Unable to open ${intent.component} in main", error)
                    }
                },
                onSidebarSideRequested = onSidebarSideRequested,
                onSettingsRequested = onSettingsRequested,
                onExitRequested = onExitRequested,
            ).also { topAppStrip = it },
            width = geometry.screenWidth,
            height = geometry.topHeight,
            gravity = Gravity.TOP,
            touchable = true,
            windowType = windowType,
        )
        addWindow(
            title = "NeXtepSidebar",
            view = sidebarPanel(geometry),
            width = geometry.sidebarWidth,
            height = geometry.contentHeight,
            gravity = Gravity.TOP or if (sidebarSide == SidebarSide.LEFT) {
                Gravity.START
            } else {
                Gravity.END
            },
            y = geometry.topHeight,
            touchable = FeatureGate.TASK_SWAP.defaultEnabled,
            windowType = windowType,
        )
        addWindow(
            title = "NeXtepContentPanel",
            view = FrameLayout(context).apply { setBackgroundColor(Color.TRANSPARENT) },
            width = geometry.contentWidth,
            height = geometry.contentHeight,
            gravity = Gravity.TOP or if (sidebarSide == SidebarSide.LEFT) {
                Gravity.END
            } else {
                Gravity.START
            },
            y = geometry.topHeight,
            windowType = windowType,
        )
        if (records.size != EXPECTED_WINDOW_COUNT) {
            records.forEach { record ->
                try {
                    windowManager.removeView(record.view)
                } catch (error: Throwable) {
                    NeXtepLog.warn("systemui_window", "Cleanup failed for ${record.title}", error)
                }
            }
            records.clear()
            slotCoordinator = null
            topAppStrip = null
            sidebarView = null
            slotWindows = emptyList()
            initialized = false
        }
    }

    private fun addWindow(
        title: String,
        view: View,
        width: Int,
        height: Int,
        gravity: Int,
        y: Int = 0,
        touchable: Boolean = false,
        windowType: Int,
    ) {
        val interactionFlags = if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val params = WindowManager.LayoutParams(
            width,
            height,
            windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                interactionFlags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            this.y = y
            this.title = title
        }
        val hostedView = if (title == "NeXtepContentPanel") view else WorkspacePanelHost(context, view)
        val record = WindowRecord(hostedView, title)
        configureWindow(record, params, geometry())
        hostedView.visibility = View.GONE
        try {
            windowManager.addView(hostedView, params)
            records += record
            NeXtepLog.info("systemui_window", "Added $title")
        } catch (error: Throwable) {
            NeXtepLog.error("systemui_window", "Could not add $title", error)
        }
    }

    private fun applyWindowGeometry(geometry: WorkspaceGeometry) {
        records.forEach { record ->
            val params = record.view.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            configureWindow(record, params, geometry)
            windowManager.updateViewLayout(record.view, params)
        }
        topAppStrip?.setLandscape(geometry.isLandscape)
    }

    private fun configureWindow(
        record: WindowRecord,
        params: WindowManager.LayoutParams,
        geometry: WorkspaceGeometry,
    ) {
        (record.view as? WorkspacePanelHost)?.landscape = geometry.isLandscape
        params.gravity = Gravity.TOP or Gravity.LEFT
        when (record.title) {
            "NeXtepTopBar" -> {
                params.width = geometry.controlWidth
                params.height = geometry.controlHeight
                params.x = geometry.controlLeft
                params.y = 0
            }
            "NeXtepSidebar" -> {
                params.width = geometry.sidebarPhysicalWidth
                params.height = geometry.sidebarPhysicalHeight
                params.x = geometry.sidebarLeft
                params.y = geometry.sidebarTop
            }
            "NeXtepContentPanel" -> {
                params.width = geometry.contentWidth
                params.height = geometry.contentHeight
                params.x = geometry.contentLeft
                params.y = geometry.contentTop
            }
        }
    }

    private fun sidebarPanel(geometry: WorkspaceGeometry): View = object : FrameLayout(context) {
        private val dividerPaint = android.graphics.Paint().apply {
            color = Color.argb(90, 255, 255, 255)
        }

        override fun dispatchDraw(canvas: android.graphics.Canvas) {
            super.dispatchDraw(canvas)
            val thickness = resources.displayMetrics.density
            val edge = if (logicalSidebarSide(this@NeXtepWindowController.geometry()) == SidebarSide.LEFT) width - thickness else 0f
            canvas.drawRect(edge, 0f, edge + thickness, height.toFloat(), dividerPaint)
            slotWindows.drop(1).forEach { slot ->
                val y = slot.view.top.toFloat()
                canvas.drawRect(0f, y - thickness, width.toFloat(), y, dividerPaint)
            }
        }
    }.apply {
        sidebarView = this
        setBackgroundColor(Color.TRANSPARENT)
        if (FeatureGate.VIRTUAL_DISPLAY_SLOTS.defaultEnabled) {
            slotWindows = List(WORKSPACE_SLOT_COUNT) { index -> VirtualDisplaySlot(context, index) }
            slotWindows.forEach { slot -> addView(slot.view) }
            applySlotLayout(this, geometry)
            slotCoordinator = SlotTaskCoordinator(
                context = context,
                slots = slotWindows,
                mainTaskPresenter = mainTaskPresenter,
                geometryProvider = ::geometry,
            )
        }
    }

    private fun slotBounds(geometry: WorkspaceGeometry, density: Float): List<SlotBounds> {
        val gap = (SLOT_GAP_DP * density).toInt().coerceAtLeast(1)
        val availableHeight = (geometry.sidebarLogicalHeight - gap * (WORKSPACE_SLOT_COUNT - 1))
            .coerceAtLeast(WORKSPACE_SLOT_COUNT)
        val baseHeight = availableHeight / WORKSPACE_SLOT_COUNT
        val remainder = availableHeight % WORKSPACE_SLOT_COUNT
        return List(WORKSPACE_SLOT_COUNT) { index ->
            val extraBefore = minOf(index, remainder)
            SlotBounds(
                topMargin = index * (baseHeight + gap) + extraBefore,
                height = baseHeight + if (index < remainder) 1 else 0,
                marginStart = if (logicalSidebarSide(geometry) == SidebarSide.RIGHT) gap else 0,
                marginEnd = if (logicalSidebarSide(geometry) == SidebarSide.LEFT) gap else 0,
            )
        }
    }

    private fun applySlotLayout(panel: FrameLayout, geometry: WorkspaceGeometry) {
        panel.invalidate()
        val bounds = slotBounds(geometry, panel.resources.displayMetrics.density)
        slotWindows.forEachIndexed { index, slot ->
            slot.view.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                bounds[index].height,
                Gravity.TOP,
            ).apply {
                marginStart = bounds[index].marginStart
                marginEnd = bounds[index].marginEnd
                topMargin = bounds[index].topMargin
            }
        }
    }

    private fun refreshWallpaperBackdrop() {
        val geometry = geometry()
        wallpaperBitmap = WorkspaceWallpaperBackdrop.capture(
            context,
            geometry.screenWidth,
            geometry.screenHeight,
        )
        records.firstOrNull { it.title == "NeXtepTopBar" }?.view?.background =
            WorkspaceWallpaperBackdrop.crop(wallpaperBitmap, geometry.controlLeft, 0)
        applySidebarBackdrop(geometry)
    }

    private fun applySidebarBackdrop(geometry: WorkspaceGeometry) {
        records.firstOrNull { it.title == "NeXtepSidebar" }?.view?.background =
            WorkspaceWallpaperBackdrop.crop(wallpaperBitmap, geometry.sidebarLeft, geometry.sidebarTop)
    }

    private fun logicalSidebarSide(geometry: WorkspaceGeometry): SidebarSide =
        if (!geometry.isLandscape) sidebarSide else
            if (sidebarSide == SidebarSide.RIGHT) SidebarSide.LEFT else SidebarSide.RIGHT

    private fun dp(value: Int): Float = value * context.resources.displayMetrics.density

    private fun geometry(): WorkspaceGeometry {
        val metrics = context.resources.displayMetrics
        return WorkspaceGeometry.forDisplay(
            metrics.widthPixels,
            metrics.heightPixels,
            sidebarSide,
        )
    }

    private data class SlotBounds(
        val topMargin: Int,
        val height: Int,
        val marginStart: Int,
        val marginEnd: Int,
    )

    private val transitionInterpolator = DecelerateInterpolator(1.6f)

    private companion object {
        const val EXPECTED_WINDOW_COUNT = 3
        const val SLOT_GAP_DP = 1f
        const val WORKSPACE_ENTER_DURATION_MS = 240L
        const val WORKSPACE_EXIT_DURATION_MS = 180L
        const val SIDEBAR_SWITCH_DURATION_MS = 220L
        const val ENTER_OFFSET_DP = 12
        const val EXIT_OFFSET_DP = 8
        const val SIDEBAR_ENTER_OFFSET_DP = 28
    }
}
