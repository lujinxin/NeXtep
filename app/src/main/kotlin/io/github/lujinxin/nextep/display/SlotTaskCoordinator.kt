package io.github.lujinxin.nextep.display

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import io.github.lujinxin.nextep.framework.ActivityTaskManagerCompat
import io.github.lujinxin.nextep.framework.WindowContainerTransactionCompat
import io.github.lujinxin.nextep.framework.UserTargetedActivityLauncher
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.task.MainTaskPresentationCoordinator
import io.github.lujinxin.nextep.task.TaskRepository
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry
import io.github.lujinxin.nextep.workspace.RecentTaskSelectionContract
import io.github.lujinxin.nextep.workspace.RecentTaskSelection
import io.github.lujinxin.nextep.workspace.SelectedRecentTask

class SlotTaskCoordinator(
    context: Context,
    private val slots: List<VirtualDisplaySlot>,
    private val mainTaskPresenter: MainTaskPresentationCoordinator,
    private val geometryProvider: () -> WorkspaceGeometry,
    private val onDragActiveChanged: (Boolean) -> Unit = {},
    private val onSwapAnimation: (SlotTaskDrag, Int?, () -> Unit) -> Unit = { _, _, move -> move() },
    private val onInternalSlotDrag: (TaskSwitcherView, SlotTaskDrag) -> Boolean = { _, _ -> false },
    private val onInternalDragTouch: (android.view.View, android.view.MotionEvent) -> Boolean = { _, _ -> false },
) : VirtualDisplaySlot.Listener, TaskSwitcherView.Listener {
    private data class SlotRecord(
        var state: SlotState = SlotState.Empty,
        var retainedTaskId: Int? = null,
        var exitTask: TaskRepository.RetainedTask? = null,
        var pendingComponent: ComponentName? = null,
        var pendingAttempts: Int = 0,
        var originalLayout: TaskLayout? = null,
        var mismatchSince: Long = 0L,
        var migrationRecoveryComponent: ComponentName? = null,
        var migrationRecoveryDeadline: Long = 0L,
        var migrationRecoveryAttempts: Int = 0,
    )

    private data class TaskLayout(
        val bounds: Rect,
        val densityDpi: Int,
        val windowingMode: Int,
        val resizeMode: Int?,
    )

    private val applicationContext = context.applicationContext ?: context
    private val packageManager = applicationContext.packageManager
    private val taskRepository = TaskRepository(applicationContext)
    private val handler = Handler(Looper.getMainLooper())
    private val slotRecords = MutableList(slots.size) { SlotRecord() }
    private var active = false
    private var suspended = false
    private var transitionInProgress = false
    private var recentSelectionPending = false
    private var recentSelectionGeneration = 0
    private var activeDrag: SlotTaskDrag? = null
    private var dragTransitionPending = false
    private var reconciliationTick = 0
    private var pendingSlotTap: Int? = null
    private var promotedRecoveryComponent: ComponentName? = null
    private var promotedRecoveryDeadline = 0L
    private var promotedRecoveryAttempts = 0

    private val reconciliation = object : Runnable {
        override fun run() {
            if (!active || suspended) return
            if (!transitionInProgress) {
                reconcileForeground("periodic")
                reconciliationTick += 1
                if (reconciliationTick >= FULL_RECONCILE_TICKS) {
                    reconciliationTick = 0
                    reconcile("periodic")
                }
            }
            handler.postDelayed(this, FOREGROUND_RECONCILE_INTERVAL_MS)
        }
    }

    init {
        require(slots.size == WORKSPACE_SLOT_COUNT) {
            "P6 requires exactly $WORKSPACE_SLOT_COUNT slots"
        }
        slots.forEach { slot ->
            slot.setListener(this)
            slot.view.setListener(this)
        }
    }

    fun activate() {
        ensureMainThread()
        if (active) return
        validateExitedTasks()
        active = true
        reconciliationTick = 0
        slots.forEach(VirtualDisplaySlot::activate)
        handler.removeCallbacks(reconciliation)
        handler.postDelayed(reconciliation, FOREGROUND_RECONCILE_INTERVAL_MS)
        NeXtepLog.info("slot_coordinator", "Activated retained=${retainedTaskDescription()}")
    }

    fun deactivate() {
        ensureMainThread()
        if (!active) return
        // Never destroy a display while a task failed to leave it.
        try {
            parkTasksBeforeExit()
        } catch (error: Throwable) {
            slots.forEachIndexed { index, slot ->
                if (slotRecords[index].exitTask != null) {
                    slot.displayId()?.let { onDisplayReady(index, it) }
                }
            }
            throw error
        }
        active = false
        cancelPendingDragExchange()
        onSlotDragEnded()
        recentSelectionGeneration += 1
        recentSelectionPending = false
        suspended = false
        transitionInProgress = false
        pendingSlotTap = null
        clearPromotedTaskRecovery()
        handler.removeCallbacksAndMessages(null)
        slots.forEach { slot ->
            slot.view.setBusy(false)
            slot.deactivate()
        }
        slotRecords.forEach { record ->
            record.pendingComponent = null
            record.pendingAttempts = 0
            record.migrationRecoveryComponent = null
            record.migrationRecoveryDeadline = 0L
            record.migrationRecoveryAttempts = 0
        }
        NeXtepLog.info("slot_coordinator", "Deactivated retained=${retainedTaskDescription()}")
    }

    private fun parkTasksBeforeExit() {
        val tasks = taskRepository.runningTasks()
        val saved = mutableSetOf<Int>()
        slots.forEachIndexed { index, slot ->
            val record = slotRecords[index]
            record.exitTask = null
            val task = tasks.firstOrNull { it.displayId == slot.displayId() }
            record.retainedTaskId = null
            if (task == null || !saved.add(task.taskId)) return@forEachIndexed
            runCatching {
                val layout = record.originalLayout ?: defaultMainLayout(task.resizeMode)
                WindowContainerTransactionCompat.parkInDefaultDisplay(
                    checkNotNull(task.token), layout.bounds, layout.densityDpi, layout.windowingMode,
                ).getOrThrow()
                check(taskRepository.findTask(task.taskId)?.displayId == Display.DEFAULT_DISPLAY) {
                    "Task ${task.taskId} did not return to the default display"
                }
                layout.resizeMode?.let { ActivityTaskManagerCompat.setTaskResizeable(task.taskId, it).getOrThrow() }
                val recent = taskRepository.recentTasks(checkNotNull(task.userId)).getOrThrow()
                    .firstOrNull { it.taskId == task.taskId }
                record.exitTask = recent
                record.retainedTaskId = recent?.taskId
                NeXtepLog.info("slot_retention", "Parked slot=$index task=${recent?.taskId}")
            }.onFailure {
                NeXtepLog.warn("slot_retention", "Could not retain slot=$index task=${task.taskId}", it)
            }
            // Some apps open secondary tasks on the same display. Preserve those in recents
            // too, while only the top task owns this slot's restore reservation.
            tasks.filter { it.displayId == slot.displayId() && it.taskId != task.taskId }
                .forEach { secondary ->
                    val layout = defaultMainLayout(secondary.resizeMode)
                    WindowContainerTransactionCompat.parkInDefaultDisplay(
                        checkNotNull(secondary.token), layout.bounds, layout.densityDpi, layout.windowingMode,
                    ).getOrThrow()
                }
        }
        val displays = slots.mapNotNull { it.displayId() }.toSet()
        check(taskRepository.runningTasks().none { it.displayId in displays }) {
            "Slot tasks are still attached; refusing to destroy their displays"
        }
    }

    private fun validateExitedTasks() {
        val queries = slotRecords.mapNotNull { it.exitTask?.userId }.distinct()
            .associateWith { taskRepository.recentTasks(it).getOrNull() }
        val foreground = taskRepository.foregroundTask()?.taskId
        slotRecords.forEach { record ->
            val saved = record.exitTask ?: return@forEach
            val current = queries[saved.userId]?.firstOrNull { it.taskId == saved.taskId }
            val live = taskRepository.findTask(saved.taskId)
            if (current != saved || foreground == saved.taskId ||
                (live != null && (live.displayId != Display.DEFAULT_DISPLAY || live.vendorWindowed))) {
                record.exitTask = null
                record.retainedTaskId = null
                record.originalLayout = null
                NeXtepLog.info("slot_retention", "Discarded changed/removed task=${saved.taskId}")
            }
        }
    }

    fun setSuspended(value: Boolean) {
        ensureMainThread()
        if (!active || suspended == value) return
        suspended = value
        if (value) {
            cancelPendingDragExchange()
            onSlotDragEnded()
            recentSelectionGeneration += 1
            recentSelectionPending = false
            handler.removeCallbacksAndMessages(null)
            transitionInProgress = false
            pendingSlotTap = null
            clearPromotedTaskRecovery()
            slots.forEach { it.view.setBusy(true) }
        } else {
            slots.forEach { it.view.setBusy(false) }
            handler.post(reconciliation)
        }
    }

    fun launchInSlot(intent: Intent, slotIndex: Int = 0): Result<Unit> {
        ensureMainThread()
        return assignIntent(slotIndex, intent)
    }

    fun openInMain(sourceIntent: Intent): Result<Unit> {
        ensureMainThread()
        if (!active || suspended || transitionInProgress) {
            return Result.failure(IllegalStateException("Workspace transition is busy"))
        }
        val normalized = normalizeLaunchIntent(sourceIntent)
            ?: return Result.failure(IllegalArgumentException("App does not resolve to a launcher Activity"))
        val component = checkNotNull(normalized.component)
        val existing = taskRepository.findTaskForComponent(component, normalized.targetUser())

        return runTransition("open main component=$component") {
            val currentMain = taskRepository.foregroundTask()
            if (currentMain != null && currentMain.taskId != existing?.taskId) {
                mainTaskPresenter.restoreForMove(currentMain.taskId).getOrThrow()
            }

            if (existing != null) {
                val sourceSlot = slotRecords.indexOfFirst { record ->
                    (record.state as? SlotState.Occupied)?.taskId == existing.taskId
                }
                val sourceLayout = sourceSlot.takeIf { it >= 0 }
                    ?.let { slotRecords[it].originalLayout }
                    ?: defaultMainLayout(existing.resizeMode)
                mainTaskPresenter.restoreForMove(existing.taskId).getOrThrow()
                if (existing.displayId != Display.DEFAULT_DISPLAY) {
                    ActivityTaskManagerCompat.setTaskResizeable(
                        existing.taskId,
                        TASK_RESIZE_MODE_RESIZEABLE,
                    ).getOrThrow()
                    moveTaskToDisplayAndWait(
                        existing.taskId,
                        Display.DEFAULT_DISPLAY,
                    )
                    restoreTaskLayout(existing, sourceLayout, restoreResizeMode = false)
                }
                if (sourceSlot >= 0) {
                    slotRecords[sourceSlot].retainedTaskId = null
                    slotRecords[sourceSlot].pendingComponent = null
                    slotRecords[sourceSlot].originalLayout = null
                    val displayId = slots[sourceSlot].displayId()
                    setState(
                        sourceSlot,
                        displayId?.let(SlotState::Ready) ?: SlotState.Empty,
                        "top app promoted from slot",
                    )
                }
                taskRepository.bringTaskToFront(existing.taskId).getOrThrow()
                mainTaskPresenter.presentTask(existing.taskId, geometryProvider()).getOrThrow()
            } else {
                mainTaskPresenter.restoreForeground().getOrThrow()
                UserTargetedActivityLauncher.start(applicationContext, normalized).getOrThrow()
                handler.postDelayed({
                    presentLaunchedMain(component, attempt = 1)
                }, MAIN_LAUNCH_POLL_MS)
            }
        }
    }

    fun openMediaInMain(session: android.media.session.MediaController): Result<Unit> {
        ensureMainThread()
        if (!active || suspended || transitionInProgress) {
            return Result.failure(IllegalStateException("Workspace transition is busy"))
        }
        val targetUser = session.sessionActivity?.creatorUserHandle ?: android.os.Process.myUserHandle()
        val userId = io.github.lujinxin.nextep.framework.TaskInfoCompat.userIdentifier(targetUser)
        val sourceSlot = slotRecords.indexOfFirst { record ->
            val occupied = record.state as? SlotState.Occupied ?: return@indexOfFirst false
            occupied.component.packageName == session.packageName &&
                taskRepository.findTask(occupied.taskId)?.userId == userId
        }
        if (sourceSlot >= 0) {
            val occupied = slotRecords[sourceSlot].state as SlotState.Occupied
            return runTransition("media swap slot=$sourceSlot task=${occupied.taskId}") {
                swapWithMain(sourceSlot, occupied)
            }
        }
        val launcherIntent = packageManager.getLaunchIntentForPackage(session.packageName)
            ?.putExtra(TriggerBroadcastContract.EXTRA_TARGET_USER, targetUser)
        if (launcherIntent != null) return openInMain(launcherIntent)

        // Some media sessions have a player Activity but no launcher entry.
        val pendingIntent = session.sessionActivity
            ?: return Result.failure(IllegalStateException("Media app has no launch target"))
        if (!pendingIntent.isActivity) {
            return Result.failure(IllegalStateException("Media session launch target is not an Activity"))
        }
        return runTransition("media session main package=${session.packageName}") {
            mainTaskPresenter.restoreForeground().getOrThrow()
            val options = android.app.ActivityOptions.makeBasic().apply { setLaunchDisplayId(Display.DEFAULT_DISPLAY) }
            pendingIntent.send(applicationContext, 0, null, null, null, null, options.toBundle())
            handler.postDelayed({ reconcileForeground("media session launched") }, MAIN_LAUNCH_POLL_MS)
        }
    }

    fun openExternalInMain(sourceIntent: Intent): Result<Unit> {
        ensureMainThread()
        if (!active || suspended || transitionInProgress) {
            return Result.failure(IllegalStateException("Workspace transition is busy"))
        }
        val normalized = normalizeExternalIntent(sourceIntent)
            ?: return Result.failure(IllegalArgumentException("External content has no Activity"))
        val component = checkNotNull(normalized.component)
        return runTransition("open external component=$component") {
            taskRepository.foregroundTask()?.let { currentMain ->
                mainTaskPresenter.restoreForMove(currentMain.taskId).getOrThrow()
            }
            UserTargetedActivityLauncher.start(applicationContext, normalized).getOrThrow()
            handler.postDelayed({
                presentLaunchedMain(component, attempt = 1)
            }, MAIN_LAUNCH_POLL_MS)
        }
    }

    fun openFromLauncher(sourceIntent: Intent): Result<Unit> {
        ensureMainThread()
        if (!active) return Result.failure(IllegalStateException("Workspace is inactive"))
        val normalized = normalizeLaunchIntent(sourceIntent)
            ?: return Result.failure(IllegalArgumentException("App does not resolve to a launcher Activity"))
        val component = checkNotNull(normalized.component)
        val existing = taskRepository.findTaskForComponent(component, normalized.targetUser())
        val sourceSlot = existing?.let { task ->
            slotRecords.indexOfFirst { record ->
                (record.state as? SlotState.Occupied)?.taskId == task.taskId
            }
        } ?: -1
        if (sourceSlot >= 0) {
            onSlotClicked(sourceSlot)
            NeXtepLog.info(
                "launcher_redirect",
                "Converted repeated launch into slot exchange slot=$sourceSlot taskId=${existing?.taskId}",
            )
            return Result.success(Unit)
        }
        return openInMain(normalized)
    }

    private fun presentLaunchedMain(component: ComponentName, attempt: Int) {
        if (!active) return
        val launched = taskRepository.findTaskForComponent(component)
            ?.takeIf { it.displayId == Display.DEFAULT_DISPLAY }
        if (launched == null) {
            if (attempt < MAX_MAIN_LAUNCH_POLLS) {
                handler.postDelayed(
                    { presentLaunchedMain(component, attempt + 1) },
                    MAIN_LAUNCH_POLL_MS,
                )
            } else {
                NeXtepLog.warn(
                    "top_apps",
                    "Main task did not appear component=$component attempts=$attempt",
                )
            }
            return
        }
        mainTaskPresenter.presentTask(launched.taskId, geometryProvider())
            .onFailure { error ->
                NeXtepLog.error(
                    "top_apps",
                    "Main presentation failed taskId=${launched.taskId}",
                    error,
                )
            }
    }

    fun slotStates(): List<SlotState> = slotRecords.map { it.state }

    override fun onDisplayReady(slotIndex: Int, displayId: Int) {
        ensureMainThread()
        if (!active) return
        validateExitedTasks()
        setState(slotIndex, SlotState.Ready(displayId), "display ready")
        val retainedTaskId = slotRecords[slotIndex].retainedTaskId
        if (retainedTaskId != null) {
            val exited = slotRecords[slotIndex].exitTask
            if (exited != null && taskRepository.findTask(retainedTaskId) == null) {
                taskRepository.restoreRecentTask(retainedTaskId, displayId).onFailure {
                    NeXtepLog.warn("slot_retention", "Recent task restore failed task=$retainedTaskId", it)
                }
            }
            slotRecords[slotIndex].exitTask = null
            val retained = taskRepository.findTask(retainedTaskId)
            if (retained == null) {
                slotRecords[slotIndex].retainedTaskId = null
                slotRecords[slotIndex].originalLayout = null
            } else {
                val wasPresented = mainTaskPresenter.currentTaskId() == retainedTaskId
                runCatching {
                    mainTaskPresenter.restoreForMove(retainedTaskId).getOrThrow()
                    ActivityTaskManagerCompat.setTaskResizeable(
                        retainedTaskId,
                        TASK_RESIZE_MODE_RESIZEABLE,
                    ).getOrThrow()
                    moveTaskToDisplayAndWait(retainedTaskId, displayId)
                    ensureSlotLayout(slotIndex, retained)
                }
                    .onSuccess {
                        setState(
                            slotIndex,
                            SlotState.Occupied(displayId, retained.taskId, retained.component),
                            "retained task rebound",
                        )
                    }
                    .onFailure { error ->
                        if (wasPresented) {
                            mainTaskPresenter.presentTask(retainedTaskId, geometryProvider())
                                .onFailure(error::addSuppressed)
                        }
                        slotRecords[slotIndex].retainedTaskId = null
                        slotRecords[slotIndex].originalLayout = null
                        setState(
                            slotIndex,
                            SlotState.Failed("restore failed"),
                            "retained task rebind failed: ${error.message}",
                        )
                    }
            }
        }
        handler.postDelayed({ reconcile("display ready") }, SETTLE_DELAY_MS)
    }

    override fun onDisplayReleased(slotIndex: Int, displayId: Int?, reason: String) {
        ensureMainThread()
        setState(slotIndex, SlotState.Empty, "display released id=$displayId reason=$reason")
    }

    override fun onDisplayFailed(slotIndex: Int, message: String) {
        ensureMainThread()
        setState(slotIndex, SlotState.Failed(message), "display failed")
    }

    override fun onSlotClicked(slotIndex: Int) {
        if (!active || suspended || slotIndex !in slots.indices) return
        if (recentSelectionPending) return
        if (transitionInProgress) {
            pendingSlotTap = slotIndex
            NeXtepLog.info("slot_coordinator", "Queued slot tap slot=$slotIndex")
            return
        }
        when (val state = slotRecords[slotIndex].state) {
            is SlotState.Occupied -> runTransition(
                "swap slot=$slotIndex taskId=${state.taskId}",
            ) {
                swapWithMain(slotIndex, state)
            }
            is SlotState.Ready -> {
                val mainTask = taskRepository.foregroundTask()
                if (mainTask != null && mainTask.component.packageName == TriggerBroadcastContract.resolveHomePackage(applicationContext)) {
                    requestRecentTask(slotIndex, state, mainTask.taskId)
                    return
                }
                if (!isExchangeableMainTask(mainTask)) {
                    NeXtepLog.info(
                        "slot_coordinator",
                        "Ignored empty slot tap slot=$slotIndex; main task is unavailable or HOME",
                    )
                    return
                }
                val parkedTask = checkNotNull(mainTask)
                runTransition(
                    "park main taskId=${parkedTask.taskId} slot=$slotIndex",
                ) {
                    parkMainTaskInSlot(slotIndex, state, parkedTask)
                }
            }
            else -> Unit
        }
    }

    private fun requestRecentTask(slotIndex: Int, ready: SlotState.Ready, homeTaskId: Int) {
        recentSelectionPending = true
        val generation = ++recentSelectionGeneration
        slots.forEach { it.view.setBusy(true) }
        RecentTaskSelectionContract.query(applicationContext) { selection ->
            if (generation != recentSelectionGeneration) return@query
            recentSelectionPending = false
            if (!active || suspended) return@query
            slots.forEach { it.view.setBusy(false) }
            // Reject a delayed result after another app took the foreground or
            // this slot/display was repurposed by another workspace operation.
            if (slotRecords[slotIndex].state != ready || slots[slotIndex].displayId() != ready.displayId ||
                taskRepository.foregroundTask()?.taskId != homeTaskId || transitionInProgress
            ) return@query
            when (selection) {
                RecentTaskSelection.NotVisible -> Unit // HOME itself stays outside slots.
                is RecentTaskSelection.Unavailable -> showMessage(selection.message)
                is RecentTaskSelection.Selected -> runTransition(
                    "center recent task=${selection.task.taskId} slot=$slotIndex",
                ) { assignRecentTask(slotIndex, ready, selection.task) }.onFailure {
                    showMessage("该后台任务暂时无法加入小窗，请重新选择后重试")
                }
            }
        }
    }

    private fun assignRecentTask(slotIndex: Int, ready: SlotState.Ready, selected: SelectedRecentTask) {
        val retained = taskRepository.recentTasks(selected.userId).getOrThrow()
            .firstOrNull { it.taskId == selected.taskId && it.baseComponent.packageName == selected.component.packageName }
            ?: error("Selected recent task changed or was removed")
        val live = taskRepository.findTask(retained.taskId)
        if (live != null) {
            check(live.userId == selected.userId) { "Selected task user changed" }
            // ColorOS includes tasks on our virtual displays in overview. Move
            // that exact task between slots instead of rejecting it or launching
            // a second instance. The destination is still the queried empty slot.
            val sourceSlot = slotRecords.indexOfFirst { record ->
                (record.state as? SlotState.Occupied)?.taskId == live.taskId
            }
            if (sourceSlot >= 0) {
                check(sourceSlot != slotIndex && slots[sourceSlot].displayId() == live.displayId) {
                    "Selected slot task changed"
                }
                swapSlots(sourceSlot, slotIndex)
                return
            }
            check(isExchangeableMainTask(live)) {
                "Selected task is no longer on the default display"
            }
            parkMainTaskInSlot(slotIndex, ready, live, leaveOverview = false)
            return
        }
        // Restore the exact retained task, including its user and back stack; do not
        // launch by package (that could open a different card or a fresh Activity).
        val record = slotRecords[slotIndex]
        var restored: TaskRepository.TaskSnapshot? = null
        var original = defaultMainLayout(null)
        try {
            taskRepository.restoreRecentTask(retained.taskId, ready.displayId).getOrThrow()
            val task = checkNotNull(taskRepository.findTask(retained.taskId)) { "Restored task is unavailable" }
            restored = task
            check(task.userId == selected.userId && task.component.packageName == selected.component.packageName)
            original = defaultMainLayout(task.resizeMode)
            ActivityTaskManagerCompat.setTaskResizeable(task.taskId, TASK_RESIZE_MODE_RESIZEABLE).getOrThrow()
            if (task.displayId != ready.displayId) moveTaskToDisplayAndWait(task.taskId, ready.displayId)
            record.originalLayout = original
            ensureSlotLayout(slotIndex, task, original)
            record.retainedTaskId = task.taskId
            setState(slotIndex, SlotState.Occupied(ready.displayId, task.taskId, task.component), "center recent restored")
        } catch (error: Throwable) {
            record.originalLayout = null
            record.retainedTaskId = null
            restored?.let { task ->
                runCatching {
                    WindowContainerTransactionCompat.parkInDefaultDisplay(
                        checkNotNull(task.token), original.bounds, original.densityDpi, original.windowingMode,
                    ).getOrThrow()
                    original.resizeMode?.let { mode ->
                        ActivityTaskManagerCompat.setTaskResizeable(task.taskId, mode).getOrThrow()
                    }
                }.onFailure(error::addSuppressed)
            }
            throw error
        }
    }

    private fun showMessage(message: String) {
        android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onIntentDropped(slotIndex: Int, intent: Intent): Boolean {
        if (!validActiveSlot(slotIndex)) return false
        return assignIntent(slotIndex, intent).isSuccess
    }

    override fun onSlotDragStarting(drag: SlotTaskDrag): Boolean {
        if (!active || suspended || transitionInProgress || recentSelectionPending || activeDrag != null ||
            !matchesDrag(drag)
        ) return false
        activeDrag = drag
        onDragActiveChanged(true)
        return true
    }

    override fun onSlotDragEnded() {
        activeDrag = null
        onDragActiveChanged(false)
    }

    override fun onSlotDragStarted(source: TaskSwitcherView, drag: SlotTaskDrag) = onInternalSlotDrag(source, drag)
    override fun onSlotDragTouch(source: android.view.View, event: android.view.MotionEvent) = onInternalDragTouch(source, event)

    fun currentDrag(): SlotTaskDrag? = activeDrag

    /** Return below existing tasks in one transaction, keeping the main app and
     * overview visible. This releases slot ownership without closing the task. */
    fun dismissDraggedSlot(drag: SlotTaskDrag): Boolean {
        if (activeDrag != drag || !matchesDrag(drag) || !active || suspended || recentSelectionPending) return false
        return runTransition("background slot=${drag.slotIndex} task=${drag.taskId}") {
            val task = checkNotNull(taskRepository.findTask(drag.taskId))
            val record = slotRecords[drag.slotIndex]
            val layout = record.originalLayout ?: defaultMainLayout(task.resizeMode)
            try {
                WindowContainerTransactionCompat.parkInDefaultDisplay(
                    checkNotNull(task.token), layout.bounds, layout.densityDpi, layout.windowingMode,
                ).getOrThrow()
                check(taskRepository.findTask(task.taskId)?.displayId == Display.DEFAULT_DISPLAY)
                layout.resizeMode?.let { ActivityTaskManagerCompat.setTaskResizeable(task.taskId, it).getOrThrow() }
                record.exitTask = null
                updateDraggedSlot(drag.slotIndex, SlotState.Ready(drag.displayId), null)
                showMessage("已移到后台")
            } catch (error: Throwable) {
                runCatching {
                    moveTaskToDisplayAndWait(task.taskId, drag.displayId)
                    ensureSlotLayout(drag.slotIndex, task, layout)
                }.onFailure(error::addSuppressed)
                throw error
            }
        }.isSuccess
    }

    override fun onSlotDropped(slotIndex: Int, drag: SlotTaskDrag): Boolean {
        if (activeDrag != drag || !validActiveSlot(slotIndex) || !matchesDrag(drag)) return false
        if (slotIndex == drag.slotIndex) return true
        val target = slotRecords[slotIndex].state
        if (target !is SlotState.Ready && target !is SlotState.Occupied) return false
        return runTransition("drag slot=${drag.slotIndex} to slot=$slotIndex", drag to slotIndex) {
            check(matchesDrag(drag) && slotRecords[slotIndex].state == target) { "Drag target changed" }
            swapSlots(drag.slotIndex, slotIndex)
        }.isSuccess
    }

    fun dropSlotIntoMain(drag: SlotTaskDrag): Boolean {
        if (activeDrag != drag || !matchesDrag(drag) || !active || suspended || recentSelectionPending) return false
        val mainTaskId = taskRepository.foregroundTask()?.taskId
        return runTransition("drag slot=${drag.slotIndex} to main", drag to null) {
            check(matchesDrag(drag) && taskRepository.foregroundTask()?.taskId == mainTaskId) { "Main task changed during drag" }
            swapWithMain(drag.slotIndex, slotRecords[drag.slotIndex].state as SlotState.Occupied)
        }.isSuccess
    }

    private fun matchesDrag(drag: SlotTaskDrag): Boolean {
        val state = slotRecords.getOrNull(drag.slotIndex)?.state as? SlotState.Occupied ?: return false
        return state.taskId == drag.taskId && state.displayId == drag.displayId &&
            slots[drag.slotIndex].displayId() == drag.displayId &&
            taskRepository.findTask(drag.taskId)?.displayId == drag.displayId
    }

    fun cancelPendingDragExchange() {
        if (!dragTransitionPending) return
        dragTransitionPending = false
        transitionInProgress = false
        pendingSlotTap = null
        slots.forEach { it.view.setBusy(false) }
    }

    private fun swapSlots(sourceIndex: Int, targetIndex: Int) {
        val sourceState = slotRecords[sourceIndex].state as SlotState.Occupied
        val targetState = slotRecords[targetIndex].state
        val sourceTask = checkNotNull(taskRepository.findTask(sourceState.taskId))
        val targetOccupied = targetState as? SlotState.Occupied
        val targetTask = targetOccupied?.let { checkNotNull(taskRepository.findTask(it.taskId)) }
        val targetDisplay = checkNotNull(slots[targetIndex].displayId())
        check(sourceTask.displayId == sourceState.displayId)
        check(targetTask == null || targetTask.displayId == targetDisplay)
        val sourceRecord = slotRecords[sourceIndex]
        val targetRecord = slotRecords[targetIndex]
        val sourceLayout = sourceRecord.originalLayout ?: defaultMainLayout(sourceTask.resizeMode)
        val targetLayout = targetTask?.let { targetRecord.originalLayout ?: defaultMainLayout(it.resizeMode) }
        try {
            // Both tasks stay on virtual displays; no temporary foreground app is
            // exposed on display 0. Preview frames cover the intermediate reparent.
            if (targetTask != null) moveTaskToDisplayAndWait(targetTask.taskId, sourceState.displayId)
            moveTaskToDisplayAndWait(sourceTask.taskId, targetDisplay)
            targetRecord.originalLayout = sourceLayout
            ensureSlotLayout(targetIndex, sourceTask, sourceLayout)
            if (targetTask != null) {
                sourceRecord.originalLayout = targetLayout
                ensureSlotLayout(sourceIndex, targetTask, targetLayout)
            }
            updateDraggedSlot(targetIndex, SlotState.Occupied(targetDisplay, sourceTask.taskId, sourceTask.component), sourceLayout)
            updateDraggedSlot(sourceIndex, if (targetTask != null) {
                SlotState.Occupied(sourceState.displayId, targetTask.taskId, targetTask.component)
            } else SlotState.Ready(sourceState.displayId), targetLayout)
        } catch (error: Throwable) {
            runCatching {
                moveTaskToDisplayAndWait(sourceTask.taskId, sourceState.displayId)
                sourceRecord.originalLayout = sourceLayout
                ensureSlotLayout(sourceIndex, sourceTask, sourceLayout)
            }.onFailure(error::addSuppressed)
            if (targetTask != null) runCatching {
                moveTaskToDisplayAndWait(targetTask.taskId, targetDisplay)
                targetRecord.originalLayout = targetLayout
                ensureSlotLayout(targetIndex, targetTask, targetLayout)
            }.onFailure(error::addSuppressed)
            updateDraggedSlot(sourceIndex, sourceState, sourceLayout)
            updateDraggedSlot(targetIndex, targetState, targetLayout)
            throw error
        }
    }

    private fun updateDraggedSlot(index: Int, state: SlotState, layout: TaskLayout?) {
        val record = slotRecords[index]
        record.retainedTaskId = (state as? SlotState.Occupied)?.taskId
        record.originalLayout = if (state is SlotState.Occupied) layout else null
        record.pendingComponent = null
        record.pendingAttempts = 0
        record.migrationRecoveryComponent = null
        record.migrationRecoveryDeadline = 0L
        record.migrationRecoveryAttempts = 0
        record.mismatchSince = 0L
        setState(index, state, "drag exchange")
    }

    private fun assignIntent(slotIndex: Int, sourceIntent: Intent): Result<Unit> {
        if (!validActiveSlot(slotIndex)) {
            return Result.failure(IllegalStateException("Slot $slotIndex is unavailable"))
        }
        val normalized = normalizeLaunchIntent(sourceIntent)
            ?: return Result.failure(IllegalArgumentException("Drop does not resolve to an App"))
        val component = checkNotNull(normalized.component)
        if (slotRecords[slotIndex].pendingComponent != null) {
            return Result.failure(IllegalStateException("Slot $slotIndex launch is pending"))
        }
        val targetDisplayId = slots[slotIndex].displayId()
            ?: return Result.failure(IllegalStateException("Slot $slotIndex display is pending"))
        val existing = taskRepository.findTaskForComponent(component, normalized.targetUser())
        val occupied = slotRecords[slotIndex].state as? SlotState.Occupied

        if (existing?.displayId == targetDisplayId) {
            slotRecords[slotIndex].retainedTaskId = existing.taskId
            val layoutResult = runCatching { ensureSlotLayout(slotIndex, existing) }
            if (layoutResult.isFailure) {
                return Result.failure(checkNotNull(layoutResult.exceptionOrNull()))
            }
            setState(
                slotIndex,
                SlotState.Occupied(targetDisplayId, existing.taskId, existing.component),
                "drop matched target task",
            )
            return Result.success(Unit)
        }

        if (occupied != null) {
            val foreground = taskRepository.foregroundTask()
            if (existing != null && foreground?.taskId == existing.taskId) {
                return runTransition("drop main into occupied slot=$slotIndex") {
                    swapWithMain(slotIndex, occupied)
                }
            }
            slots[slotIndex].view.showState(occupied)
            return Result.failure(IllegalStateException("Slot $slotIndex is occupied"))
        }

        if (existing != null) {
            return runTransition("move dropped taskId=${existing.taskId} slot=$slotIndex") {
                val wasPresented = mainTaskPresenter.currentTaskId() == existing.taskId
                val sourceSlot = slotRecords.indexOfFirst { record ->
                    (record.state as? SlotState.Occupied)?.taskId == existing.taskId
                }
                var restoredTask = existing
                var originalLayout = sourceSlot.takeIf { it >= 0 }
                    ?.let { slotRecords[it].originalLayout }
                    ?: defaultMainLayout(existing.resizeMode)
                var moved = false
                try {
                    mainTaskPresenter.restoreForMove(existing.taskId).getOrThrow()
                    restoredTask = taskRepository.findTask(existing.taskId) ?: existing
                    originalLayout = if (sourceSlot >= 0) {
                        slotRecords[sourceSlot].originalLayout
                    } else {
                        restoredTask.toLayout()
                    } ?: defaultMainLayout(restoredTask.resizeMode)
                    ActivityTaskManagerCompat.setTaskResizeable(
                        existing.taskId,
                        TASK_RESIZE_MODE_RESIZEABLE,
                    ).getOrThrow()
                    moveTaskToDisplayAndWait(existing.taskId, targetDisplayId)
                    moved = true
                    slotRecords[slotIndex].originalLayout = originalLayout
                    ensureSlotLayout(slotIndex, restoredTask, originalLayout)
                } catch (error: Throwable) {
                    slotRecords[slotIndex].originalLayout = null
                    if (moved) {
                        runCatching {
                            moveTaskToDisplayAndWait(
                                existing.taskId,
                                existing.displayId,
                            )
                            if (sourceSlot >= 0) {
                                ensureSlotLayout(sourceSlot, restoredTask, originalLayout)
                            } else {
                                restoreTaskLayout(restoredTask, originalLayout)
                            }
                        }.onFailure(error::addSuppressed)
                    }
                    if (wasPresented) {
                        mainTaskPresenter.presentTask(existing.taskId, geometryProvider())
                            .onFailure(error::addSuppressed)
                    }
                    throw error
                }
                if (sourceSlot >= 0 && sourceSlot != slotIndex) {
                    slotRecords[sourceSlot].retainedTaskId = null
                    slotRecords[sourceSlot].originalLayout = null
                    val sourceDisplayId = slots[sourceSlot].displayId()
                    setState(
                        sourceSlot,
                        sourceDisplayId?.let { SlotState.Ready(it) } ?: SlotState.Empty,
                        "task moved to another slot",
                    )
                }
                slotRecords[slotIndex].retainedTaskId = existing.taskId
                setState(
                    slotIndex,
                    SlotState.Occupied(targetDisplayId, existing.taskId, existing.component),
                    "existing task assigned",
                )
            }
        }

        slotRecords[slotIndex].pendingComponent = component
        slotRecords[slotIndex].pendingAttempts = 0
        slots[slotIndex].view.showWaiting()
        return runTransition("launch component=$component slot=$slotIndex") {
            try {
                slots[slotIndex].launch(normalized).getOrThrow()
                handler.postDelayed({ reconcile("launch requested") }, SETTLE_DELAY_MS)
            } catch (error: Throwable) {
                slotRecords[slotIndex].pendingComponent = null
                setState(
                    slotIndex,
                    SlotState.Failed("launch failed"),
                    error.message ?: "launch failed",
                )
                throw error
            }
        }
    }

    private fun parkMainTaskInSlot(
        slotIndex: Int,
        ready: SlotState.Ready,
        mainTask: TaskRepository.TaskSnapshot,
        leaveOverview: Boolean = true,
    ) {
        val targetDisplayId = slots[slotIndex].displayId()
            ?: error("Slot $slotIndex display is unavailable")
        check(targetDisplayId == ready.displayId) {
            "Slot $slotIndex display changed ${ready.displayId}->$targetDisplayId"
        }
        val record = slotRecords[slotIndex]
        val wasPresented = mainTaskPresenter.currentTaskId() == mainTask.taskId
        var restoredMainTask = mainTask
        var mainOriginalLayout = mainTask.toLayout()
        var preparedForMove = false
        var movedToSlot = false
        try {
            preparedForMove = true
            restoredMainTask = taskRepository.findTask(mainTask.taskId) ?: mainTask
            mainOriginalLayout = restoredMainTask.toLayout()
            if (restoredMainTask.resizeMode != TASK_RESIZE_MODE_RESIZEABLE) {
                ActivityTaskManagerCompat.setTaskResizeable(
                    mainTask.taskId,
                    TASK_RESIZE_MODE_RESIZEABLE,
                ).getOrThrow()
            }
            record.migrationRecoveryComponent = mainTask.component
            record.migrationRecoveryDeadline =
                SystemClock.uptimeMillis() + MIGRATION_RECOVERY_WINDOW_MS
            record.migrationRecoveryAttempts = 0
            moveTaskToDisplayAndWait(mainTask.taskId, targetDisplayId)
            movedToSlot = true
            // Never expose the identity-sized task on Display 0 between restore and move.
            // Its logical layout is unchanged by the presentation transform.
            mainTaskPresenter.restoreForMove(mainTask.taskId).getOrThrow()
            record.originalLayout = mainOriginalLayout
            ensureSlotLayout(slotIndex, restoredMainTask, mainOriginalLayout)
            if (leaveOverview) showHomeOnDefaultDisplay()

            slotRecords.forEachIndexed { index, otherRecord ->
                val occupiedTaskId = (otherRecord.state as? SlotState.Occupied)?.taskId
                if (index != slotIndex &&
                    (otherRecord.retainedTaskId == mainTask.taskId ||
                        occupiedTaskId == mainTask.taskId)
                ) {
                    otherRecord.retainedTaskId = null
                    otherRecord.pendingComponent = null
                    otherRecord.pendingAttempts = 0
                    otherRecord.originalLayout = null
                    val displayId = slots[index].displayId()
                    setState(
                        index,
                        displayId?.let(SlotState::Ready) ?: SlotState.Empty,
                        "duplicate parked task ownership cleared",
                    )
                }
            }
            record.retainedTaskId = mainTask.taskId
            setState(
                slotIndex,
                SlotState.Occupied(targetDisplayId, mainTask.taskId, mainTask.component),
                "main task parked in empty slot",
            )
        } catch (error: Throwable) {
            val rollbackErrors = mutableListOf<Throwable>()
            record.retainedTaskId = null
            record.originalLayout = null
            record.migrationRecoveryComponent = null
            record.migrationRecoveryDeadline = 0L
            record.migrationRecoveryAttempts = 0
            if (preparedForMove) {
                runCatching {
                    if (movedToSlot) {
                        moveTaskToDisplayAndWait(
                            mainTask.taskId,
                            Display.DEFAULT_DISPLAY,
                        )
                    }
                    restoreTaskLayout(restoredMainTask, mainOriginalLayout)
                }.onFailure(rollbackErrors::add)
            }
            if (wasPresented) {
                mainTaskPresenter.presentTask(mainTask.taskId, geometryProvider())
                    .onFailure(rollbackErrors::add)
            }
            if (rollbackErrors.isEmpty()) {
                setState(slotIndex, ready, "empty slot parking rolled back")
            } else {
                setState(
                    slotIndex,
                    SlotState.Failed("rollback failed"),
                    "empty slot parking rollback incomplete",
                )
                rollbackErrors.forEach(error::addSuppressed)
            }
            throw error
        }
    }

    private fun showHomeOnDefaultDisplay() {
        val homePackage = TriggerBroadcastContract.resolveHomePackage(applicationContext)
            ?: error("No resolved HOME package")
        val homeIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage(homePackage)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        applicationContext.startActivity(homeIntent)
    }

    private fun isExchangeableMainTask(task: TaskRepository.TaskSnapshot?): Boolean {
        val mainTask = task ?: return false
        val homePackage = TriggerBroadcastContract.resolveHomePackage(applicationContext)
            ?: return false
        val packageName = mainTask.component.packageName
        return mainTask.displayId == Display.DEFAULT_DISPLAY &&
            !mainTask.vendorWindowed && mainTask.windowingMode == fullscreenWindowingMode() &&
            packageName != homePackage &&
            packageName != TriggerBroadcastContract.SYSTEM_UI_PACKAGE
    }

    private fun swapWithMain(slotIndex: Int, occupied: SlotState.Occupied) {
        val slotTask = taskRepository.findTask(occupied.taskId)
            ?: error("Slot task ${occupied.taskId} exited")
        val mainTask = taskRepository.foregroundTask()
        val record = slotRecords[slotIndex]
        val slotOriginalLayout = record.originalLayout
            ?: defaultMainLayout(slotTask.resizeMode)
        val mainIsExchangeable = mainTask != null &&
            isExchangeableMainTask(mainTask) &&
            mainTask.taskId != slotTask.taskId

        var slotMovedToMain = false
        var mainMovedToSlot = false
        var restoredMainTask: TaskRepository.TaskSnapshot? = null
        var mainOriginalLayout: TaskLayout? = null
        try {
            promotedRecoveryComponent = slotTask.component
            promotedRecoveryDeadline =
                SystemClock.uptimeMillis() + MIGRATION_RECOVERY_WINDOW_MS
            promotedRecoveryAttempts = 0
            if (mainIsExchangeable) {
                restoredMainTask = taskRepository.findTask(mainTask.taskId) ?: mainTask
                mainOriginalLayout = restoredMainTask.toLayout()
                if (restoredMainTask.resizeMode != TASK_RESIZE_MODE_RESIZEABLE) {
                    ActivityTaskManagerCompat.setTaskResizeable(
                        mainTask.taskId,
                        TASK_RESIZE_MODE_RESIZEABLE,
                    ).getOrThrow()
                }
            }

            if (slotTask.resizeMode != TASK_RESIZE_MODE_RESIZEABLE) {
                ActivityTaskManagerCompat.setTaskResizeable(
                    slotTask.taskId,
                    TASK_RESIZE_MODE_RESIZEABLE,
                ).getOrThrow()
            }
            moveTaskToDisplayAndWait(slotTask.taskId, Display.DEFAULT_DISPLAY)
            slotMovedToMain = true
            restoreTaskLayout(slotTask, slotOriginalLayout, restoreResizeMode = false)
            taskRepository.bringTaskToFront(slotTask.taskId).getOrThrow()

            if (mainIsExchangeable) {
                record.migrationRecoveryComponent = mainTask.component
                record.migrationRecoveryDeadline =
                    SystemClock.uptimeMillis() + MIGRATION_RECOVERY_WINDOW_MS
                record.migrationRecoveryAttempts = 0
                moveTaskToDisplayAndWait(mainTask.taskId, occupied.displayId)
                mainMovedToSlot = true
                mainTaskPresenter.restoreForMove(mainTask.taskId).getOrThrow()
                record.originalLayout = mainOriginalLayout
                ensureSlotLayout(slotIndex, checkNotNull(restoredMainTask), mainOriginalLayout)
                record.retainedTaskId = mainTask.taskId
                setState(
                    slotIndex,
                    SlotState.Occupied(
                        occupied.displayId,
                        mainTask.taskId,
                        mainTask.component,
                    ),
                    "main and slot exchanged",
                )
            } else {
                record.retainedTaskId = null
                record.originalLayout = null
                setState(slotIndex, SlotState.Ready(occupied.displayId), "slot promoted to main")
            }
            val presentation = mainTaskPresenter.presentTask(slotTask.taskId, geometryProvider())
            if (presentation.isFailure && taskRepository.findTask(slotTask.taskId) != null) {
                presentation.getOrThrow()
            }
            schedulePromotedTaskRecovery()
        } catch (error: Throwable) {
            clearPromotedTaskRecovery()
            val rollbackErrors = mutableListOf<Throwable>()
            if (slotMovedToMain) {
                mainTaskPresenter.restoreForMove(slotTask.taskId)
                    .onFailure(rollbackErrors::add)
                runCatching {
                    moveTaskToDisplayAndWait(slotTask.taskId, occupied.displayId)
                    record.originalLayout = slotOriginalLayout
                    ensureSlotLayout(slotIndex, slotTask, slotOriginalLayout)
                }.onFailure(rollbackErrors::add)
            }
            val rollbackMainTask = restoredMainTask
            if (mainMovedToSlot && mainTask != null && rollbackMainTask != null) {
                runCatching {
                    moveTaskToDisplayAndWait(
                        mainTask.taskId,
                        Display.DEFAULT_DISPLAY,
                    )
                    restoreTaskLayout(
                        rollbackMainTask,
                        checkNotNull(mainOriginalLayout),
                    )
                }.onFailure(rollbackErrors::add)
            }
            if (mainTask != null && rollbackMainTask != null) {
                mainTaskPresenter.presentTask(mainTask.taskId, geometryProvider())
                    .onFailure(rollbackErrors::add)
            }
            record.retainedTaskId = occupied.taskId
            record.originalLayout = slotOriginalLayout
            record.migrationRecoveryComponent = null
            record.migrationRecoveryDeadline = 0L
            record.migrationRecoveryAttempts = 0
            if (rollbackErrors.isEmpty()) {
                setState(slotIndex, occupied, "swap rolled back")
            } else {
                slotRecords[slotIndex].retainedTaskId = null
                slotRecords[slotIndex].originalLayout = null
                setState(slotIndex, SlotState.Failed("rollback failed"), "swap rollback incomplete")
                rollbackErrors.forEach(error::addSuppressed)
            }
            throw error
        }
    }

    private fun runTransition(label: String, animation: Pair<SlotTaskDrag, Int?>? = null, operation: () -> Unit): Result<Unit> {
        if (transitionInProgress || recentSelectionPending) {
            NeXtepLog.warn("slot_coordinator", "Ignored concurrent transition $label")
            return Result.failure(IllegalStateException("Another slot transition is settling"))
        }
        transitionInProgress = true
        slots.forEach { it.view.setBusy(true) }
        NeXtepLog.info("slot_coordinator", "Transition started $label")
        fun performMove(): Result<Unit> {
            if (!active || suspended) return Result.failure(IllegalStateException("Workspace is no longer interactive"))
            val result = runCatching(operation).onFailure { error ->
                NeXtepLog.error("slot_coordinator", "Transition failed $label", error)
                if (animation != null) showMessage("交换未完成，已尝试恢复原窗口")
            }
            handler.postDelayed({
                transitionInProgress = false
                slots.forEach { it.view.setBusy(false) }
                reconcile("settled $label")
                NeXtepLog.info("slot_coordinator", "Transition settled $label")
                val queuedSlot = pendingSlotTap
                pendingSlotTap = null
                if (queuedSlot != null) onSlotClicked(queuedSlot)
            }, SETTLE_DELAY_MS)
            return result
        }
        if (animation == null) return performMove()
        dragTransitionPending = true
        return runCatching {
            onSwapAnimation(animation.first, animation.second) {
                if (dragTransitionPending) {
                    dragTransitionPending = false
                    performMove()
                }
            }
        }.onFailure {
            dragTransitionPending = false
            transitionInProgress = false
            slots.forEach { it.view.setBusy(false) }
        }
    }

    private fun reconcile(reason: String) {
        ensureMainThread()
        if (!active || suspended || transitionInProgress) return
        val tasks = taskRepository.runningTasks()
        val assignedTaskIds = mutableSetOf<Int>()

        slots.forEachIndexed { index, slot ->
            val displayId = slot.displayId() ?: run {
                setState(index, SlotState.Empty, "$reason: no display")
                return@forEachIndexed
            }
            val displayTasks = tasks.filter { it.displayId == displayId }
            val displayTask = displayTasks.firstOrNull()
            if (displayTasks.size > 1) {
                NeXtepLog.warn(
                    "slot_state",
                    "slot=$index displayId=$displayId stackedTasks=${displayTasks.map { it.taskId }}",
                )
            }
            val retainedTask = slotRecords[index].retainedTaskId
                ?.let { taskId -> tasks.firstOrNull { it.taskId == taskId } }
            val record = slotRecords[index]
            val recoveringSameComponent = record.migrationRecoveryComponent
                ?.packageName == displayTask?.component?.packageName &&
                SystemClock.uptimeMillis() <= record.migrationRecoveryDeadline

            when {
                displayTask != null && assignedTaskIds.add(displayTask.taskId) -> {
                    slotRecords[index].mismatchSince = 0L
                    if (record.retainedTaskId != null &&
                        record.retainedTaskId != displayTask.taskId &&
                        !recoveringSameComponent
                    ) {
                        record.originalLayout = null
                    }
                    try {
                        ensureSlotLayout(index, displayTask)
                    } catch (error: Throwable) {
                        setState(
                            index,
                            SlotState.Failed("layout rejected"),
                            "$reason: ${error.message}",
                        )
                        return@forEachIndexed
                    }
                    record.retainedTaskId = displayTask.taskId
                    record.pendingComponent = null
                    record.pendingAttempts = 0
                    setState(
                        index,
                        SlotState.Occupied(displayId, displayTask.taskId, displayTask.component),
                        "$reason: adopted display task",
                    )
                }
                displayTask != null -> {
                    slotRecords[index].retainedTaskId = null
                    slotRecords[index].originalLayout = null
                    setState(index, SlotState.Failed("duplicate task"), "$reason: duplicate record")
                }
                retainedTask != null && !assignedTaskIds.add(retainedTask.taskId) -> {
                    slotRecords[index].retainedTaskId = null
                    slotRecords[index].originalLayout = null
                    setState(index, SlotState.Failed("duplicate task"), "$reason: retained duplicate")
                }
                retainedTask != null -> {
                    val foregroundTaskId = taskRepository.foregroundTask()?.taskId
                    if (retainedTask.displayId == Display.DEFAULT_DISPLAY &&
                        retainedTask.taskId == foregroundTaskId
                    ) {
                        val originalLayout = slotRecords[index].originalLayout
                            ?: defaultMainLayout(retainedTask.resizeMode)
                        runCatching {
                            restoreTaskLayout(
                                retainedTask,
                                originalLayout,
                                restoreResizeMode = false,
                            )
                            slotRecords[index].retainedTaskId = null
                            slotRecords[index].pendingComponent = null
                            slotRecords[index].pendingAttempts = 0
                            slotRecords[index].originalLayout = null
                            setState(
                                index,
                                SlotState.Ready(displayId),
                                "$reason: task externally promoted to main",
                            )
                            mainTaskPresenter.reconcileForeground(geometryProvider()).getOrThrow()
                        }.onFailure { error ->
                            setState(
                                index,
                                SlotState.Failed("main promotion failed"),
                                "$reason: ${error.message}",
                            )
                        }
                        return@forEachIndexed
                    }
                    val now = SystemClock.uptimeMillis()
                    if (record.mismatchSince == 0L) record.mismatchSince = now
                    if (now - record.mismatchSince >= SLOT_MISMATCH_GRACE_MS) {
                        assignedTaskIds -= retainedTask.taskId
                        record.retainedTaskId = null
                        record.pendingComponent = null
                        record.pendingAttempts = 0
                        record.originalLayout = null
                        record.mismatchSince = 0L
                        setState(
                            index,
                            SlotState.Ready(displayId),
                            "$reason: stale slot ownership cleared",
                        )
                    }
                }
                shouldRecoverMigratedTask(record) -> {
                    recoverMigratedTask(index, record)
                }
                slotRecords[index].pendingComponent != null &&
                    slotRecords[index].pendingAttempts < MAX_PENDING_POLLS -> {
                    slotRecords[index].pendingAttempts += 1
                    slot.view.showWaiting()
                }
                else -> {
                    slotRecords[index].retainedTaskId = null
                    slotRecords[index].originalLayout = null
                    val launchTimedOut = slotRecords[index].pendingComponent != null
                    slotRecords[index].pendingComponent = null
                    slotRecords[index].pendingAttempts = 0
                    setState(
                        index,
                        if (launchTimedOut) SlotState.Failed("launch timed out")
                        else SlotState.Ready(displayId),
                        "$reason: empty",
                    )
                }
            }
        }
        mainTaskPresenter.reconcileForeground(geometryProvider())
            .onFailure { error ->
                NeXtepLog.warn(
                    "main_task_presenter",
                    "Foreground reconciliation failed reason=$reason",
                    error,
                )
            }
    }

    private fun shouldRecoverMigratedTask(record: SlotRecord): Boolean =
        record.migrationRecoveryComponent != null &&
            record.migrationRecoveryAttempts < MAX_MIGRATION_RECOVERY_ATTEMPTS &&
            SystemClock.uptimeMillis() <= record.migrationRecoveryDeadline

    private fun schedulePromotedTaskRecovery() {
        handler.postDelayed(::recoverPromotedTaskIfNeeded, PROMOTED_RECOVERY_DELAY_MS)
    }

    private fun recoverPromotedTaskIfNeeded() {
        val component = promotedRecoveryComponent ?: return
        if (!active || SystemClock.uptimeMillis() > promotedRecoveryDeadline) {
            clearPromotedTaskRecovery()
            return
        }
        val promotedTask = taskRepository.findTaskForComponent(component)
            ?.takeIf { task -> task.displayId == Display.DEFAULT_DISPLAY }
        if (promotedTask != null) {
            mainTaskPresenter.presentTask(promotedTask.taskId, geometryProvider())
                .onSuccess {
                    NeXtepLog.info(
                        "slot_coordinator",
                        "Promoted task settled taskId=${promotedTask.taskId} component=$component",
                    )
                    clearPromotedTaskRecovery()
                }
                .onFailure { error ->
                    NeXtepLog.warn(
                        "slot_coordinator",
                        "Promoted task presentation still settling taskId=${promotedTask.taskId}",
                        error,
                    )
                    handler.postDelayed(
                        ::recoverPromotedTaskIfNeeded,
                        PROMOTED_RECOVERY_POLL_MS,
                    )
                }
            return
        }
        if (promotedRecoveryAttempts >= MAX_MIGRATION_RECOVERY_ATTEMPTS) {
            NeXtepLog.warn(
                "slot_coordinator",
                "Promoted task recovery exhausted component=$component",
            )
            clearPromotedTaskRecovery()
            return
        }
        val intent = normalizeLaunchIntent(Intent().setComponent(component))
        if (intent == null) {
            NeXtepLog.warn(
                "slot_coordinator",
                "Promoted task recovery has no launcher Activity component=$component",
            )
            clearPromotedTaskRecovery()
            return
        }
        promotedRecoveryAttempts += 1
        runCatching { applicationContext.startActivity(intent) }
            .onSuccess {
                NeXtepLog.warn(
                    "slot_coordinator",
                    "Relaunched promoted task after process death component=$component",
                )
                handler.postDelayed(
                    ::recoverPromotedTaskIfNeeded,
                    PROMOTED_RECOVERY_POLL_MS,
                )
            }
            .onFailure { error ->
                NeXtepLog.error(
                    "slot_coordinator",
                    "Promoted task recovery launch failed component=$component",
                    error,
                )
                clearPromotedTaskRecovery()
            }
    }

    private fun clearPromotedTaskRecovery() {
        promotedRecoveryComponent = null
        promotedRecoveryDeadline = 0L
        promotedRecoveryAttempts = 0
    }

    private fun recoverMigratedTask(slotIndex: Int, record: SlotRecord) {
        val component = checkNotNull(record.migrationRecoveryComponent)
        val intent = normalizeLaunchIntent(Intent().setComponent(component))
        if (intent == null) {
            record.migrationRecoveryComponent = null
            record.migrationRecoveryDeadline = 0L
            setState(
                slotIndex,
                SlotState.Failed("migration recovery unavailable"),
                "No launcher Activity for $component",
            )
            return
        }
        record.migrationRecoveryAttempts += 1
        record.retainedTaskId = null
        record.pendingComponent = component
        record.pendingAttempts = 0
        slots[slotIndex].view.showWaiting()
        slots[slotIndex].launch(intent)
            .onSuccess {
                NeXtepLog.warn(
                    "slot_coordinator",
                    "Relaunched migrated task after process death slot=$slotIndex " +
                        "component=$component attempt=${record.migrationRecoveryAttempts}",
                )
            }
            .onFailure { error ->
                record.pendingComponent = null
                NeXtepLog.error(
                    "slot_coordinator",
                    "Migration recovery launch failed slot=$slotIndex component=$component",
                    error,
                )
            }
    }

    private fun reconcileForeground(reason: String) {
        val foreground = taskRepository.foregroundTask() ?: return
        val promotedSlot = slotRecords.indexOfFirst { record ->
            record.retainedTaskId == foreground.taskId &&
                foreground.displayId == Display.DEFAULT_DISPLAY
        }
        if (promotedSlot >= 0) {
            val record = slotRecords[promotedSlot]
            val originalLayout = record.originalLayout
                ?: defaultMainLayout(foreground.resizeMode)
            runCatching {
                restoreTaskLayout(foreground, originalLayout, restoreResizeMode = false)
                record.retainedTaskId = null
                record.pendingComponent = null
                record.pendingAttempts = 0
                record.originalLayout = null
                val displayId = slots[promotedSlot].displayId()
                setState(
                    promotedSlot,
                    displayId?.let(SlotState::Ready) ?: SlotState.Empty,
                    "$reason: foreground task left slot",
                )
            }.onFailure { error ->
                NeXtepLog.warn(
                    "slot_state",
                    "Unable to adopt externally promoted taskId=${foreground.taskId}",
                    error,
                )
                return
            }
        }
        mainTaskPresenter.reconcileForeground(geometryProvider())
            .onFailure { error ->
                NeXtepLog.warn(
                    "main_task_presenter",
                    "Fast foreground reconciliation failed reason=$reason",
                    error,
                )
            }
    }

    private fun normalizeLaunchIntent(source: Intent): Intent? {
        val component = source.component ?: packageManager.resolveActivity(source, 0)
            ?.activityInfo
            ?.let { ComponentName(it.packageName, it.name) }
            ?: return null
        val info = runCatching { packageManager.getActivityInfo(component, 0) }.getOrNull()
            ?: return null
        if (!info.exported || !info.enabled) return null
        return Intent(source)
            .setComponent(component)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun normalizeExternalIntent(source: Intent): Intent? {
        val component = source.component ?: packageManager.resolveActivity(source, 0)
            ?.activityInfo
            ?.let { ComponentName(it.packageName, it.name) }
            ?: return null
        val info = runCatching { packageManager.getActivityInfo(component, 0) }.getOrNull()
            ?: return null
        if (!info.exported || !info.enabled) return null
        return Intent(source)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun Intent.targetUser() = getParcelableExtra(
        TriggerBroadcastContract.EXTRA_TARGET_USER,
        android.os.UserHandle::class.java,
    )

    private fun ensureSlotLayout(
        slotIndex: Int,
        task: TaskRepository.TaskSnapshot,
        originalLayout: TaskLayout? = null,
    ) {
        val slotGeometry = slots[slotIndex].geometry()
            ?: error("Slot $slotIndex geometry is unavailable")
        val fullscreenMode = fullscreenWindowingMode()
        val targetBounds = Rect(0, 0, slotGeometry.width, slotGeometry.height)
        val record = slotRecords[slotIndex]
        if (record.originalLayout == null) {
            record.originalLayout = originalLayout ?: if (task.displayId == Display.DEFAULT_DISPLAY) {
                task.toLayout()
            } else {
                defaultMainLayout(task.resizeMode)
            }
        }
        val resizeModeMismatch = task.resizeMode != TASK_RESIZE_MODE_RESIZEABLE
        val layoutMismatch = task.bounds != targetBounds ||
            task.densityDpi != slotGeometry.densityDpi ||
            task.windowingMode != fullscreenMode
        if (resizeModeMismatch) {
            ActivityTaskManagerCompat.setTaskResizeable(
                task.taskId,
                TASK_RESIZE_MODE_RESIZEABLE,
            ).getOrThrow()
        }
        if (layoutMismatch) {
            val token = task.token ?: error("Task ${task.taskId} has no slot layout token")
            WindowContainerTransactionCompat.applyTaskLayout(
                token = token,
                bounds = targetBounds,
                densityDpi = slotGeometry.densityDpi,
                windowingMode = fullscreenMode,
            ).getOrThrow()
            waitForTaskLayout(
                taskId = task.taskId,
                bounds = targetBounds,
                densityDpi = slotGeometry.densityDpi,
                windowingMode = fullscreenMode,
            )
            NeXtepLog.info(
                "slot_layout",
                "Applied slot=$slotIndex taskId=${task.taskId} bounds=$targetBounds " +
                    "density=${slotGeometry.densityDpi}",
            )
        }
    }

    private fun restoreTaskLayout(
        task: TaskRepository.TaskSnapshot,
        layout: TaskLayout,
        restoreResizeMode: Boolean = true,
    ) {
        val token = task.token ?: error("Task ${task.taskId} has no restore token")
        WindowContainerTransactionCompat.applyTaskLayout(
            token = token,
            bounds = layout.bounds,
            densityDpi = layout.densityDpi,
            windowingMode = layout.windowingMode,
        ).getOrThrow()
        waitForTaskLayout(
            taskId = task.taskId,
            bounds = layout.bounds,
            densityDpi = layout.densityDpi,
            windowingMode = layout.windowingMode,
        )
        if (restoreResizeMode) {
            layout.resizeMode?.let { resizeMode ->
                ActivityTaskManagerCompat.setTaskResizeable(task.taskId, resizeMode).getOrThrow()
            }
        }
        NeXtepLog.info(
            "slot_layout",
            "Restored taskId=${task.taskId} bounds=${layout.bounds} density=${layout.densityDpi} " +
                "restoreResizeMode=$restoreResizeMode",
        )
    }

    private fun TaskRepository.TaskSnapshot.toLayout() = TaskLayout(
        bounds = Rect(bounds),
        densityDpi = densityDpi,
        windowingMode = windowingMode,
        resizeMode = resizeMode,
    )

    private fun defaultMainLayout(resizeMode: Int?): TaskLayout {
        val geometry = geometryProvider()
        return TaskLayout(
            bounds = Rect(0, 0, geometry.screenWidth, geometry.screenHeight),
            densityDpi = applicationContext.resources.displayMetrics.densityDpi,
            windowingMode = fullscreenWindowingMode(),
            resizeMode = resizeMode,
        )
    }

    private fun fullscreenWindowingMode(): Int = ActivityTaskManagerCompat
        .resolveWindowingMode("WINDOWING_MODE_FULLSCREEN")
        ?: WINDOWING_MODE_FULLSCREEN_FALLBACK

    private fun moveTaskToDisplayAndWait(taskId: Int, displayId: Int) {
        ActivityTaskManagerCompat.moveTaskToDisplay(taskId, displayId).getOrThrow()
        val deadline = SystemClock.uptimeMillis() + DISPLAY_MOVE_TIMEOUT_MS
        var observedDisplayId = taskRepository.findTask(taskId)?.displayId
        while (observedDisplayId != displayId && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(DISPLAY_MOVE_POLL_MS)
            observedDisplayId = taskRepository.findTask(taskId)?.displayId
        }
        check(observedDisplayId == displayId) {
            "Task $taskId display move did not settle: expected=$displayId " +
                "actual=$observedDisplayId"
        }
    }

    private fun waitForTaskLayout(
        taskId: Int,
        bounds: Rect,
        densityDpi: Int,
        windowingMode: Int,
    ) {
        val deadline = SystemClock.uptimeMillis() + TASK_LAYOUT_TIMEOUT_MS
        var snapshot = taskRepository.findTask(taskId)
        while (snapshot != null &&
            (snapshot.bounds != bounds ||
                snapshot.densityDpi != densityDpi ||
                snapshot.windowingMode != windowingMode) &&
            SystemClock.uptimeMillis() < deadline
        ) {
            SystemClock.sleep(TASK_LAYOUT_POLL_MS)
            snapshot = taskRepository.findTask(taskId)
        }
        check(snapshot != null &&
            snapshot.bounds == bounds &&
            snapshot.densityDpi == densityDpi &&
            snapshot.windowingMode == windowingMode
        ) {
            "Task $taskId layout did not settle: expected=$bounds/$densityDpi/$windowingMode " +
                "actual=${snapshot?.bounds}/${snapshot?.densityDpi}/${snapshot?.windowingMode}"
        }
    }

    private fun setState(index: Int, state: SlotState, reason: String) {
        val previous = slotRecords[index].state
        slotRecords[index].state = state
        if (state !is SlotState.Occupied) slotRecords[index].mismatchSince = 0L
        slots[index].view.showState(state)
        if (previous != state) {
            NeXtepLog.info(
                "slot_state",
                "slot=$index previous=$previous current=$state reason=$reason",
            )
        }
    }

    private fun validActiveSlot(index: Int): Boolean =
        active && !suspended && index in slots.indices && !transitionInProgress

    private fun retainedTaskDescription(): String =
        slotRecords.map { it.retainedTaskId }.toString()

    private fun ensureMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Slot state changes must run on the main thread"
        }
    }

    private companion object {
        const val SETTLE_DELAY_MS = 700L
        const val MAIN_LAUNCH_POLL_MS = 200L
        const val MAX_MAIN_LAUNCH_POLLS = 3
        const val FOREGROUND_RECONCILE_INTERVAL_MS = 120L
        const val FULL_RECONCILE_TICKS = 8
        const val MAX_PENDING_POLLS = 4
        const val TASK_RESIZE_MODE_RESIZEABLE = 2
        const val WINDOWING_MODE_FULLSCREEN_FALLBACK = 1
        const val DISPLAY_MOVE_TIMEOUT_MS = 700L
        const val DISPLAY_MOVE_POLL_MS = 30L
        const val TASK_LAYOUT_TIMEOUT_MS = 900L
        const val TASK_LAYOUT_POLL_MS = 30L
        const val SLOT_MISMATCH_GRACE_MS = 2_400L
        const val MIGRATION_RECOVERY_WINDOW_MS = 5_000L
        const val MAX_MIGRATION_RECOVERY_ATTEMPTS = 1
        const val PROMOTED_RECOVERY_DELAY_MS = 900L
        const val PROMOTED_RECOVERY_POLL_MS = 350L
    }
}
