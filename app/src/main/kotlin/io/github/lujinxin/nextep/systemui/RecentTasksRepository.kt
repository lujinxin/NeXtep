package io.github.lujinxin.nextep.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.drawable.Drawable
import android.hardware.HardwareBuffer
import android.os.SystemClock
import android.util.LruCache
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.apps.LauncherAppRepository
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.framework.ActivityTaskManagerCompat
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.task.TaskRepository
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import io.github.lujinxin.nextep.workspace.SelectedRecentTask
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicInteger

/** Recent-task metadata and a bounded thumbnail cache. Platform queries run on the task-page worker. */
internal class RecentTasksRepository(private val context: Context) {
    data class Entry(
        val task: SelectedRecentTask,
        val label: CharSequence,
        val icon: Drawable,
        val lastActiveTime: Long,
    )

    private val tasks = TaskRepository(context)
    private val apps = LauncherAppRepository(context)
    private var snapshotFailureLogged = false
    private var fullResolutionFallbackLogged = false
    private var missingSnapshotLogged = false
    private var catalogue: List<LauncherAppRepository.AppEntry>? = null
    private var catalogueAt = 0L
    private var catalogueLanguage = ""
    data class ThumbnailVersion(val lastActiveTime: Long, val revision: Long)
    data class Thumbnail(val version: ThumbnailVersion, val bitmap: Bitmap, val snapshotId: Long?)
    private data class SnapshotSource(val read: (Int, Boolean) -> Any?)
    private val snapshotSources by lazy { resolveSnapshotSources() }
    private val cacheEpoch = AtomicInteger()
    private val revisions = mutableMapOf<Int, Long>()
    private val thumbnailCache = object : LruCache<SelectedRecentTask, Thumbnail>(8 * 1024 * 1024) {
        override fun sizeOf(key: SelectedRecentTask, value: Thumbnail) = value.bitmap.allocationByteCount
    }

    fun cachedThumbnail(entry: Entry): Thumbnail? = thumbnailCache.get(entry.task)

    fun thumbnailVersion(entry: Entry): ThumbnailVersion = synchronized(thumbnailCache) {
        ThumbnailVersion(entry.lastActiveTime, revisions[entry.task.taskId] ?: 0L)
    }

    fun onSnapshotChanged(taskId: Int, invalidated: Boolean) {
        synchronized(thumbnailCache) {
            revisions[taskId] = (revisions[taskId] ?: 0L) + 1L
            if (invalidated) thumbnailCache.snapshot().keys.filter { it.taskId == taskId }
                .forEach { thumbnailCache.remove(it) }
        }
    }

    fun clearThumbnails() {
        synchronized(thumbnailCache) {
            cacheEpoch.incrementAndGet()
            // A displayed ImageView may still own a bitmap; let GC reclaim evicted images.
            thumbnailCache.evictAll()
            revisions.clear()
        }
    }

    fun load(excludedTaskIds: Set<Int>): List<Entry> {
        val catalogue = appCatalogue()
        val currentUser = currentUserId()
        return backgroundTasks(currentUser, catalogue, excludedTaskIds).asSequence()
            .sortedByDescending { it.lastActiveTime }
            .take(36)
            .mapNotNull { retainedTask ->
                runCatching {
                    val component = retainedTask.baseComponent
                    val app = catalogue.firstOrNull { it.userId == retainedTask.userId && it.component == component }
                        ?: catalogue.firstOrNull { it.userId == retainedTask.userId && it.component.packageName == component.packageName }
                    val manager = apps.contextForUser(retainedTask.userId)?.packageManager ?: context.packageManager
                    val info = if (app == null) manager.getApplicationInfo(component.packageName, 0) else null
                    val label = app?.label ?: checkNotNull(info).loadLabel(manager).let {
                        if (retainedTask.userId == currentUser) it
                        else AppLanguage.string(context, R.string.app_user_label, it, retainedTask.userId)
                    }
                    Entry(SelectedRecentTask(retainedTask.taskId, retainedTask.userId, component),
                        label, app?.icon ?: checkNotNull(info).loadIcon(manager), retainedTask.lastActiveTime)
                }.getOrNull()
            }.toList()
    }

    fun backgroundTaskTargets(excludedTaskIds: Set<Int>): List<SelectedRecentTask> =
        backgroundTasks(currentUserId(), appCatalogue(), excludedTaskIds).map { retained ->
            SelectedRecentTask(retained.taskId, retained.userId, retained.baseComponent)
        }

    private fun appCatalogue(): List<LauncherAppRepository.AppEntry> {
        val language = AppLanguage.locale(context).toLanguageTag()
        return catalogue?.takeIf {
            SystemClock.uptimeMillis() - catalogueAt < 60_000L && catalogueLanguage == language
        } ?: apps.load().also {
            this.catalogue = it
            catalogueAt = SystemClock.uptimeMillis()
            catalogueLanguage = language
        }
    }

    @SuppressLint("BlockedPrivateApi")
    private fun currentUserId() = runCatching {
        Class.forName("android.app.ActivityManager").getDeclaredMethod("getCurrentUser").invoke(null) as Int
    }.getOrDefault(apps.currentUserId)

    private fun backgroundTasks(
        currentUser: Int,
        catalogue: List<LauncherAppRepository.AppEntry>,
        excludedTaskIds: Set<Int>,
    ): List<TaskRepository.RetainedTask> {
        val home = TriggerBroadcastContract.resolveHomePackage(context)
        val users = (listOf(currentUser) + catalogue.map { it.userId }).distinct()
        val retained = users.flatMap { userId ->
            val result = tasks.recentTasks(userId)
            if (userId == currentUser) result.getOrThrow() else result.getOrElse {
                NeXtepLog.warn("recent_tasks", "Unable to query profile user=$userId", it)
                emptyList()
            }
        }
        return retained
            .filter { it.taskId > 0 && it.taskId !in excludedTaskIds }
            .filter { it.baseComponent.packageName !in setOf(home, context.packageName) }
            .distinctBy { it.userId to it.taskId }
    }

    fun thumbnail(entry: Entry, version: ThumbnailVersion): Thumbnail? {
        val epoch = cacheEpoch.get()
        val previous = thumbnailCache.get(entry.task)
        if (previous?.version == version) return previous
        if (version != thumbnailVersion(entry)) return null
        val fresh = readCachedThumbnail(entry.task, version, previous)
        synchronized(thumbnailCache) {
            if (epoch != cacheEpoch.get() || version != thumbnailVersion(entry)) {
                if (fresh != null && fresh.bitmap !== previous?.bitmap) fresh.bitmap.recycle()
                return null
            }
            if (fresh != null) return fresh.also { thumbnailCache.put(entry.task, it) }
        }
        return previous
    }

    fun closeTask(task: SelectedRecentTask): Result<Unit> = runCatching {
        check(tasks.recentTasks(task.userId).getOrThrow().any {
            it.taskId == task.taskId && it.userId == task.userId && it.baseComponent == task.component
        }) { "Recent task identity changed" }
        val running = tasks.findTask(task.taskId)
        check(running == null || running.displayId == android.view.Display.DEFAULT_DISPLAY &&
            tasks.foregroundTask()?.taskId != task.taskId) { "Task is visible in the workspace" }
        ActivityTaskManagerCompat.removeTask(task.taskId).getOrThrow()
    }

    private fun fitThumbnail(source: Bitmap): Bitmap {
        val scale = min(1f, min(320f / source.width, 480f / source.height))
        if (scale == 1f) return source
        return try {
            Bitmap.createScaledBitmap(source, (source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1), true)
        } finally { source.recycle() }
    }

    // Hidden APIs are invoked only inside the privileged, LSPosed-injected SystemUI process.
    @SuppressLint("BlockedPrivateApi")
    private fun resolveSnapshotSources(): List<SnapshotSource> = buildList {
        val intType = Int::class.javaPrimitiveType!!
        val boolType = Boolean::class.javaPrimitiveType!!
        runCatching {
            val type = Class.forName("android.window.TaskSnapshotManager")
            val service = Class.forName("android.app.ActivityTaskManager").getDeclaredMethod("getService").invoke(null)
            val manager = service.javaClass.getMethod("getTaskSnapshotManager")
                .apply { isAccessible = true }.invoke(service)
            val read = manager.javaClass.getMethod("getTaskSnapshot", intType, Long::class.javaPrimitiveType, intType)
                .apply { isAccessible = true }
            val low = type.getField("RESOLUTION_LOW").getInt(null)
            val high = type.getField("RESOLUTION_HIGH").getInt(null)
            // Query the remote cache directly. Its reply owns a separate buffer handle; never close
            // a snapshot wrapper borrowed from the host's process-wide TaskSnapshotManager cache.
            add(SnapshotSource { taskId, lowResolution ->
                read.invoke(manager, taskId, -1L, if (lowResolution) low else high)
            })
        }
        runCatching {
            val service = Class.forName("android.app.ActivityTaskManager").getDeclaredMethod("getService").invoke(null)
            val modern = runCatching {
                service.javaClass.getMethod("getTaskSnapshot", intType, boolType, boolType).apply { isAccessible = true }
            }.getOrNull()
            val legacy = if (modern == null) service.javaClass.getMethod("getTaskSnapshot", intType, boolType)
                .apply { isAccessible = true } else null
            add(SnapshotSource { taskId, lowResolution ->
                // Explicitly prohibit generating a new snapshot on a cache miss.
                if (modern != null) modern.invoke(service, taskId, lowResolution, false)
                else checkNotNull(legacy).invoke(service, taskId, lowResolution)
            })
        }
    }

    private fun readCachedThumbnail(task: SelectedRecentTask, version: ThumbnailVersion, previous: Thumbnail?): Thumbnail? {
        snapshotSources.forEach { source ->
            for (lowResolution in listOf(true, false)) {
                val thumbnail = runCatching {
                    val snapshot = source.read(task.taskId, lowResolution) ?: return@runCatching null
                    copySnapshot(snapshot, version, previous)
                }.getOrElse {
                    if (!snapshotFailureLogged) {
                        snapshotFailureLogged = true
                        NeXtepLog.warn("recent_tasks", "A system snapshot API is unavailable; trying other cached sources", it)
                    }
                    null
                }
                if (thumbnail != null) {
                    if (!lowResolution && !fullResolutionFallbackLogged) {
                        fullResolutionFallbackLogged = true
                        NeXtepLog.info("recent_tasks", "Using cached full-resolution system snapshots")
                    }
                    return thumbnail
                }
            }
        }
        if (!missingSnapshotLogged) {
            missingSnapshotLogged = true
            NeXtepLog.info("recent_tasks", "System snapshot unavailable; waiting for the next task or snapshot change")
        }
        return null
    }

    private fun copySnapshot(snapshot: Any, version: ThumbnailVersion, previous: Thumbnail?): Thumbnail? {
        val buffer = snapshot.javaClass.getMethod("getHardwareBuffer").apply { isAccessible = true }.invoke(snapshot) as? HardwareBuffer
            ?: return null
        return try {
            val snapshotId = runCatching { snapshot.javaClass.getMethod("getId").invoke(snapshot) as? Long }
                .getOrNull()?.takeIf { it > 0L }
            // Task metadata can change before a new snapshot exists. Reuse the same pixels in that case.
            if (snapshotId != null && previous != null && snapshotId == previous.snapshotId) {
                return Thumbnail(version, previous.bitmap, snapshotId)
            }
            val colorSpace = snapshot.javaClass.getMethod("getColorSpace").apply { isAccessible = true }.invoke(snapshot) as? ColorSpace
            val wrapped = Bitmap.wrapHardwareBuffer(buffer, colorSpace) ?: return null
            try {
                // Read back before scaling: OEM hardware buffers need not support a software Canvas.
                wrapped.copy(Bitmap.Config.ARGB_8888, false)?.let(::fitThumbnail)
                    ?.let { Thumbnail(version, it, snapshotId) }
            } finally { wrapped.recycle() }
        } finally { buffer.close() }
    }
}
