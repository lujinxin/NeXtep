package io.github.lujinxin.nextep.workspace

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

data class SelectedRecentTask(val taskId: Int, val userId: Int, val component: ComponentName)

sealed interface RecentTaskSelection {
    data object NotVisible : RecentTaskSelection
    data class Selected(val task: SelectedRecentTask) : RecentTaskSelection
    data class Unavailable(val message: String) : RecentTaskSelection
}

object RecentTaskSelectionContract {
    const val ACTION_QUERY = "io.github.lujinxin.nextep.action.QUERY_CENTER_RECENT_TASK"
    const val EXTRA_IDENTITY = "systemui_identity"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_USER_ID = "user_id"
    const val EXTRA_COMPONENT = "component"
    const val EXTRA_ERROR = "error"
    const val RESULT_NOT_VISIBLE = 35_101
    const val RESULT_SELECTED = 35_102
    const val RESULT_UNAVAILABLE = 35_103

    fun query(context: Context, callback: (RecentTaskSelection) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var complete = false
        val timeout = Runnable {
            if (!complete) {
                complete = true
                callback(RecentTaskSelection.Unavailable("后台卡片暂时无法读取，请稍后重试"))
            }
        }
        fun finish(selection: RecentTaskSelection) {
            if (complete) return
            complete = true
            handler.removeCallbacks(timeout)
            callback(selection)
        }
        val home = TriggerBroadcastContract.resolveHomePackage(context)
        if (home == null) {
            finish(RecentTaskSelection.Unavailable("无法识别系统桌面"))
            return
        }
        val identity = PendingIntent.getBroadcast(
            context, RESULT_SELECTED,
            Intent(ACTION_QUERY).setPackage(TriggerBroadcastContract.SYSTEM_UI_PACKAGE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        handler.postDelayed(timeout, 800L)
        runCatching {
            context.sendOrderedBroadcast(
                Intent(ACTION_QUERY).setPackage(home).putExtra(EXTRA_IDENTITY, identity),
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        val extras = getResultExtras(false)
                        val selection = when (resultCode) {
                            RESULT_NOT_VISIBLE -> RecentTaskSelection.NotVisible
                            RESULT_SELECTED -> {
                                val taskId = extras?.getInt(EXTRA_TASK_ID, -1) ?: -1
                                val userId = extras?.getInt(EXTRA_USER_ID, -1) ?: -1
                                val component = extras?.getString(EXTRA_COMPONENT)
                                    ?.let(ComponentName::unflattenFromString)
                                if (taskId >= 0 && userId >= 0 && component != null) {
                                    RecentTaskSelection.Selected(SelectedRecentTask(taskId, userId, component))
                                } else RecentTaskSelection.Unavailable("当前后台卡片无法加入小窗")
                            }
                            else -> RecentTaskSelection.Unavailable(
                                extras?.getString(EXTRA_ERROR) ?: "此桌面暂不支持读取后台卡片",
                            )
                        }
                        finish(selection)
                    }
                },
                handler, 0, null, null,
            )
        }.onFailure { finish(RecentTaskSelection.Unavailable("后台卡片查询失败")) }
    }
}
