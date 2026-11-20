package io.github.lujinxin.nextep.workspace

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

/** The Launcher owns the lock state. This protocol exchanges task identities and confirmed states. */
internal object RecentTaskLockContract {
    const val LAUNCHER = "com.android.launcher"
    const val PERMISSION = "android.permission.MANAGE_ACTIVITY_TASKS"
    const val QUERY = "io.github.lujinxin.nextep.action.QUERY_RECENT_TASK_LOCKS"
    const val TOGGLE = "io.github.lujinxin.nextep.action.TOGGLE_RECENT_TASK_LOCK"
    const val CLEAR = "io.github.lujinxin.nextep.action.CLEAR_UNLOCKED_RECENT_TASKS"
    const val CHANGED = "io.github.lujinxin.nextep.action.RECENT_TASK_LOCKS_CHANGED"
    const val TARGETS = "tasks"
    const val LOCKED = "locked"
    const val REMOVED = "removed"
    const val RESULT_OK = 35_204
    const val MAX_TARGETS = 36

    fun encode(task: SelectedRecentTask) = Bundle().apply {
        putInt("id", task.taskId)
        putInt("user", task.userId)
        putString("component", task.component.flattenToString())
    }

    fun decode(bundle: Bundle): SelectedRecentTask? {
        val id = bundle.getInt("id", -1)
        val user = bundle.getInt("user", -1)
        val component = bundle.getString("component")?.let(ComponentName::unflattenFromString)
        if (id <= 0 || user < 0 || component == null) return null
        return SelectedRecentTask(id, user, component)
    }

    fun isFrom(context: Context, receiver: BroadcastReceiver, packageName: String): Boolean {
        return receiver.sentFromPackage == packageName && runCatching {
            receiver.sentFromUid == context.packageManager.getApplicationInfo(packageName, 0).uid
        }.getOrDefault(false)
    }

    fun request(context: Context, action: String, tasks: List<SelectedRecentTask>, result: (BooleanArray?) -> Unit) {
        if (TriggerBroadcastContract.resolveHomePackage(context) != LAUNCHER) {
            result(null)
            return
        }
        val handler = Handler(Looper.getMainLooper())
        val resultKey = if (action == CLEAR) REMOVED else LOCKED
        var completed = false
        fun finish(states: BooleanArray?) {
            if (completed) return
            completed = true
            result(states?.takeIf { it.size == tasks.size })
        }
        val timeout = Runnable { finish(null) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                handler.removeCallbacks(timeout)
                finish(if (resultCode == RESULT_OK) getResultExtras(false)?.getBooleanArray(resultKey) else null)
            }
        }
        handler.postDelayed(timeout, 2_000L)
        runCatching {
            val intent = Intent(action).setPackage(LAUNCHER)
                .putParcelableArrayListExtra(TARGETS, ArrayList(tasks.map(::encode)))
            context.sendOrderedBroadcast(intent, PERMISSION, shareIdentity(), receiver, handler, 0, null, null)
        }.onFailure {
            handler.removeCallbacks(timeout)
            finish(null)
        }
    }

    fun notifyChanged(context: Context) {
        context.sendBroadcast(
            Intent(CHANGED).setPackage(TriggerBroadcastContract.SYSTEM_UI_PACKAGE),
            PERMISSION,
            shareIdentity(),
        )
    }

    private fun shareIdentity() = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
}
