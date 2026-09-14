package io.github.lujinxin.nextep.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.lujinxin.nextep.logging.NeXtepLog

class WorkspaceControlBrokerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val callback: (TriggerCoordinator.RequestResult) -> Unit = { result ->
            NeXtepLog.info("trigger_broker", "SystemUI request completed result=$result")
            pendingResult.finish()
        }
        when (intent.action) {
            TriggerBroadcastContract.ACTION_HOST_TOGGLE_REQUEST ->
                TriggerCoordinator.requestToggle(
                    context,
                    TriggerSource.TOP_RIGHT_STATUS_BAR_GESTURE,
                    callback,
                )
            TriggerBroadcastContract.ACTION_HOST_SET_REQUEST ->
                TriggerCoordinator.requestSet(
                    context,
                    intent.getBooleanExtra(
                        TriggerBroadcastContract.EXTRA_REQUESTED_ACTIVE,
                        false,
                    ),
                    TriggerSource.SYSTEM_RECOVERY,
                    callback,
                )
            else -> pendingResult.finish()
        }
    }
}
