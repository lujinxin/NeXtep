package io.github.lujinxin.nextep.launcher

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.Display
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.framework.ActivityTaskManagerCompat
import io.github.lujinxin.nextep.framework.TaskInfoCompat
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.task.TaskRepository
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import io.github.lujinxin.nextep.workspace.RecentTaskLockContract as Contract
import io.github.lujinxin.nextep.workspace.SelectedRecentTask

/** Calls the same ColorOS Launcher lock manager as the native recent-task menu. */
internal class ColorOsRecentTaskLocks(classLoader: ClassLoader) {
    // ColorOS Launcher 17.5.4 uses these private symbols. Incompatible signatures disable the bridge.
    private val handlerType = classLoader.loadClass("com.oplus.quickstep.applock.IAppLockDataHandler")
    private val access = classLoader.loadClass("ti7")
    private val manager = access.getDeclaredMethod("b").apply { isAccessible = true }
    private val data = manager.returnType.getDeclaredMethod("c").apply { isAccessible = true }
    private val isLocking = handlerType.getMethod("isLocking", String::class.java, String::class.java)
    private val lock = manager.returnType.getDeclaredMethod(
        "l", String::class.java, Int::class.javaPrimitiveType, Intent::class.java, Boolean::class.javaPrimitiveType,
    ).apply { isAccessible = true }
    private val unlock = manager.returnType.getDeclaredMethod(
        "p", Int::class.javaPrimitiveType, String::class.java, Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
    ).apply { isAccessible = true }
    private val notifyUi = access.getDeclaredMethod("d").apply { isAccessible = true }
    private var context: Context? = null

    init {
        check(data.returnType == handlerType && isLocking.returnType == Boolean::class.javaPrimitiveType)
        check(unlock.returnType == Void.TYPE && notifyUi.returnType == Void.TYPE)
    }

    fun installObserver(module: XposedModule) {
        module.hook(notifyUi).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                context?.let { host -> runCatching { Contract.notifyChanged(host) } }
                return result
            }
        })
    }

    fun register(host: Context) {
        if (context != null) return
        val filter = IntentFilter(Contract.QUERY).apply {
            addAction(Contract.TOGGLE)
            addAction(Contract.CLEAR)
        }
        host.registerReceiver(receiver, filter, Contract.PERMISSION, null, Context.RECEIVER_EXPORTED)
        context = host
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isOrderedBroadcast || !LauncherPackageResolver.isCurrentHome(context) ||
                !Contract.isFrom(context, this, TriggerBroadcastContract.SYSTEM_UI_PACKAGE)) return
            val request = intent.getParcelableArrayListExtra(Contract.TARGETS, Bundle::class.java) ?: return
            if (request.isEmpty() || request.size > Contract.MAX_TARGETS) return
            val targets = request.mapNotNull(Contract::decode)
            if (targets.size != request.size) return
            runCatching {
                val nativeManager = checkNotNull(manager.invoke(null))
                val lockData = checkNotNull(data.invoke(nativeManager))
                fun isLocked(task: SelectedRecentTask): Boolean {
                    return isLocking.invoke(lockData, task.component.packageName, task.userId.toString()) as Boolean
                }
                if (intent.action == Contract.TOGGLE) {
                    val task = targets.single()
                    val retained = TaskRepository(context).recentTasks(task.userId).getOrThrow().firstOrNull {
                        it.taskId == task.taskId && it.userId == task.userId && it.baseComponent == task.component
                    } ?: error("Recent task identity changed")
                    val wasLocked = isLocked(task)
                    if (wasLocked) unlock.invoke(nativeManager, task.userId, task.component.packageName, false, true)
                    else lock.invoke(nativeManager, task.component.packageName, task.userId, retained.baseIntent, true)
                    check(isLocked(task) != wasLocked) { "ColorOS did not change the lock state" }
                }
                setResultExtras(Bundle().apply {
                    if (intent.action == Contract.CLEAR) {
                        putBooleanArray(Contract.REMOVED, clearUnlocked(context, targets, ::isLocked))
                    } else {
                        putBooleanArray(Contract.LOCKED, BooleanArray(targets.size) { isLocked(targets[it]) })
                    }
                })
                resultCode = Contract.RESULT_OK
            }.onFailure { NeXtepLog.warn("recent_task_lock", "Native lock request failed", it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun clearUnlocked(
        context: Context,
        targets: List<SelectedRecentTask>,
        isLocked: (SelectedRecentTask) -> Boolean,
    ): BooleanArray {
        val tasks = TaskRepository(context)
        val retained = targets.map { it.userId }.distinct().flatMap { userId ->
            tasks.recentTasks(userId).getOrThrow()
        }.mapTo(HashSet()) { SelectedRecentTask(it.taskId, it.userId, it.baseComponent) }
        val activityManager = checkNotNull(context.getSystemService(ActivityManager::class.java))
        val running = activityManager.getRunningTasks(256)
        val protectedTaskIds = running.filter {
            TaskInfoCompat.isVisible(it) != false ||
                TaskInfoCompat.readWindowState(it)?.displayId != Display.DEFAULT_DISPLAY
        }.mapTo(HashSet()) { it.taskId }
        running.firstOrNull { TaskInfoCompat.readWindowState(it)?.displayId == Display.DEFAULT_DISPLAY }
            ?.let { protectedTaskIds.add(it.taskId) }

        // Read the native lock on the Launcher thread immediately before removing each task.
        // A stale card badge, or an unavailable lock query, must never authorize removal.
        var failed = false
        val removed = BooleanArray(targets.size) { index ->
            val target = targets[index]
            target in retained && target.taskId !in protectedTaskIds && !isLocked(target) &&
                ActivityTaskManagerCompat.removeTask(target.taskId).onFailure {
                    failed = true
                    NeXtepLog.warn("recent_task_lock", "Unable to clear recent taskId=${target.taskId}", it)
                }.isSuccess
        }
        check(!failed) { "Could not clear all eligible recent tasks" }
        return removed
    }
}
