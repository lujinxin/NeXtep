package io.github.lujinxin.nextep.display

import android.content.ClipDescription
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.DragEvent
import android.widget.Toast
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.task.TaskRepository

/** Drop on the preview to share. Promotion happens after release, never while holding content. */
internal class SlotContentDragController(
    private val context: Context,
    private val returnAfterSharing: () -> Boolean,
    private val canExchange: () -> Boolean,
    private val foregroundTask: () -> TaskRepository.TaskSnapshot?,
    private val slotTask: (Int) -> TaskRepository.TaskSnapshot?,
    private val exchange: (Int, Int, Int, (Boolean) -> Unit) -> Boolean,
    private val highlight: (Int?) -> Unit,
) {
    private data class Exchange(val slotIndex: Int, val outgoing: TaskRepository.TaskSnapshot,
        val incoming: TaskRepository.TaskSnapshot)

    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var dragging = false
    private var preparing = false
    private var switching = false
    private var finishing = false
    private val returnPolicy = ContentShareReturnPolicy()
    private var hoveredSlot: Int? = null
    private var hoveredTaskId: Int? = null
    private var previous: Exchange? = null
    private var prepared: ContentDropShare.Prepared? = null
    private var restoreDeadline = 0L
    private val restore = Runnable { restoreExchange() }
    private val expire = Runnable { cancel() }

    fun onDragEvent(slotIndex: Int, event: DragEvent): Boolean = when (event.action) {
        DragEvent.ACTION_DRAG_STARTED -> {
            if (event.localState != null || !accepts(event.clipDescription)) false
            else if (dragging) true
            else if (!canExchange() || foregroundTask() == null) false
            else {
                cancel()
                dragging = true
                handler.postDelayed(expire, DRAG_TIMEOUT_MS)
                true
            }
        }
        DragEvent.ACTION_DRAG_ENTERED -> {
            if (!dragging) false else {
                hoveredSlot = slotIndex
                hoveredTaskId = slotTask(slotIndex)?.taskId
                highlight(slotIndex.takeIf { hoveredTaskId != null })
                true
            }
        }
        DragEvent.ACTION_DRAG_LOCATION -> dragging
        DragEvent.ACTION_DRAG_EXITED -> {
            if (hoveredSlot == slotIndex) clearHover()
            true
        }
        DragEvent.ACTION_DROP -> {
            // Consume our content drop even if preparation fails. Returning false
            // lets the OEM open its own floating share card over the workspace.
            acceptDrop(slotIndex, event)
            true
        }
        DragEvent.ACTION_DRAG_ENDED -> {
            if (dragging) {
                dragging = false
                finishing = true
                handler.removeCallbacks(expire)
                clearHover()
                if (!preparing && !switching) handler.postDelayed(restore, DROP_SETTLE_MS)
            }
            true
        }
        else -> false
    }

    fun cancel() {
        generation++
        handler.removeCallbacksAndMessages(null)
        // A picker already opened by the receiving app owns its flow. Its read
        // lease expires independently; cancelling workspace work must not break it.
        prepared?.takeUnless { it.launched }?.close()
        prepared = null
        previous = null
        dragging = false
        preparing = false
        switching = false
        finishing = false
        returnPolicy.reset()
        clearHover()
    }

    private fun clearHover() {
        hoveredSlot = null
        hoveredTaskId = null
        highlight(null)
    }

    private fun acceptDrop(index: Int, event: DragEvent) {
        if (!dragging || preparing || switching || previous != null) return
        val target = slotTask(index)
        if (target == null) {
            showMessage(R.string.content_share_no_target)
            return
        }
        val main = foregroundTask()
        if (!canExchange() || main == null || target.taskId == main.taskId ||
            hoveredSlot != index || hoveredTaskId != target.taskId) {
            showMessage(R.string.content_share_failed)
            return
        }
        val payload = runCatching { ContentDropShare.read(event) }.getOrElse {
            NeXtepLog.warn("content_drag", "Dropped content is not shareable", it)
            showMessage(R.string.content_share_failed)
            return
        }
        preparing = true
        val token = generation
        ContentDropShare.prepare(context, target, payload) completed@ { result ->
            val share = result.getOrNull()
            if (token != generation) {
                share?.close()
                return@completed
            }
            preparing = false
            val currentTarget = slotTask(index)
            val currentMain = foregroundTask()
            if (share == null || !canExchange() || currentTarget == null || currentMain == null ||
                currentTarget.taskId != target.taskId || currentTarget.userId != target.userId ||
                currentMain.taskId != main.taskId || currentMain.userId != main.userId) {
                share?.close()
                result.exceptionOrNull()?.let { NeXtepLog.warn("content_drag", "Share preparation failed", it) }
                showMessage(R.string.content_share_failed)
                if (finishing) cancel()
                return@completed
            }
            prepared = share
            switching = true
            val accepted = exchange(index, main.taskId, target.taskId) exchanged@ { success ->
                if (token != generation) return@exchanged
                switching = false
                if (success) {
                    previous = Exchange(index, main, target)
                    restoreDeadline = SystemClock.uptimeMillis() + SHARE_WAIT_MS
                    val promoted = foregroundTask()
                    if (promoted?.taskId == target.taskId && promoted.userId == target.userId &&
                        promoted.component.packageName == target.component.packageName) {
                        share.launch(context).onFailure {
                            share.close()
                            showMessage(R.string.content_share_failed)
                            NeXtepLog.warn("content_drag", "Target app could not open its share page", it)
                        }
                    } else {
                        share.close()
                    }
                    NeXtepLog.info("content_drag", "Preview drop opened share task=${target.taskId} user=${target.userId} launched=${share.launched}")
                } else {
                    share.close()
                    showMessage(R.string.content_share_failed)
                }
                if (finishing) handler.postDelayed(restore, DROP_SETTLE_MS)
            }
            if (!accepted) {
                switching = false
                share.close()
                showMessage(R.string.content_share_failed)
                if (finishing) cancel()
            }
        }
    }

    private fun restoreExchange() {
        if (!finishing || preparing || switching) return
        val old = previous ?: return cancel()
        // Keep the receiver in the main pane when automatic return is disabled.
        // Failed launches still roll back the temporary exchange.
        if (prepared?.launched == true && !returnAfterSharing()) return cancel()
        val main = foregroundTask() ?: return cancel()
        val parked = slotTask(old.slotIndex) ?: return cancel()
        if (main.taskId != old.incoming.taskId || main.userId != old.incoming.userId ||
            parked.taskId != old.outgoing.taskId || parked.userId != old.outgoing.userId ||
            SystemClock.uptimeMillis() >= restoreDeadline) return cancel()
        val share = prepared
        val ready = if (share?.launched == true) {
            returnPolicy.isReady(
                original = ContentShareReturnPolicy.Activity(old.incoming.component.packageName,
                    old.incoming.component.className),
                current = ContentShareReturnPolicy.Activity(main.component.packageName, main.component.className),
                canExchange = canExchange(),
                now = SystemClock.uptimeMillis(),
            )
        } else canExchange()
        if (!ready) {
            handler.postDelayed(restore, RETRY_DELAY_MS)
            return
        }
        switching = true
        val token = generation
        val accepted = exchange(old.slotIndex, main.taskId, parked.taskId) completed@ { success ->
            if (token != generation) return@completed
            switching = false
            // Reparenting is not proof that background file reads have finished.
            // cancel() leaves launched grants to their existing bounded expiry.
            if (success) share?.finishHandoff()
            cancel()
        }
        if (!accepted) {
            switching = false
            cancel()
        }
    }

    private fun showMessage(resource: Int) = Toast.makeText(context,
        AppLanguage.string(context, resource), Toast.LENGTH_SHORT).show()

    private fun accepts(description: ClipDescription?): Boolean {
        if (description == null || AppDragContract.accepts(description)) return false
        val launchTypes = setOf("text/vnd.android.intent", "application/vnd.android.activity",
            "application/vnd.android.shortcut", "application/vnd.android.task")
        if ((0 until description.mimeTypeCount).any { description.getMimeType(it) in launchTypes }) return false
        return (0 until description.mimeTypeCount).any { index ->
            val mime = description.getMimeType(index)
            mime.startsWith("text/") || mime.startsWith("image/") || mime.startsWith("video/") ||
                mime.startsWith("audio/") || mime.startsWith("application/")
        }
    }

    private companion object {
        const val DROP_SETTLE_MS = 700L
        const val RETRY_DELAY_MS = 350L
        const val DRAG_TIMEOUT_MS = 60_000L
        const val SHARE_WAIT_MS = 5 * 60_000L
    }
}
