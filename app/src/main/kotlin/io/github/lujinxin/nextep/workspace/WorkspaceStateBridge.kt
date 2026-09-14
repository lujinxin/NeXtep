package io.github.lujinxin.nextep.workspace

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

object WorkspaceStateBridge {
    fun requestToggleFromSystemUi(context: Context) {
        try {
            sendToBroker(context, TriggerBroadcastContract.hostToggleIntent())
        } catch (error: Throwable) {
            NeXtepLog.error("workspace_bridge", "Could not reach the app-side broker", error)
        }
    }

    fun requestInactiveFromSystemUi(context: Context) {
        try {
            sendToBroker(context, TriggerBroadcastContract.hostSetIntent(false))
        } catch (error: Throwable) {
            NeXtepLog.error("workspace_bridge", "Could not request fail-open reset", error)
        }
    }

    // 0x01000000 is the framework's hidden include-background receiver flag on this target.
    @SuppressLint("WrongConstant")
    private fun sendToBroker(context: Context, intent: Intent) {
        intent.addFlags(
            Intent.FLAG_RECEIVER_FOREGROUND or FLAG_RECEIVER_INCLUDE_BACKGROUND_COMPAT,
        )
        context.sendOrderedBroadcast(intent, null)
    }

    private const val FLAG_RECEIVER_INCLUDE_BACKGROUND_COMPAT = 0x01000000
}
