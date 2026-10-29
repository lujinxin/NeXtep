package io.github.lujinxin.nextep.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

/** A read-only, signature-protected snapshot. No monitor service or background sampling. */
internal object WorkspaceEnergyContract {
    private const val ACTION = "io.github.lujinxin.nextep.action.QUERY_ENERGY_DIAGNOSTICS"
    private const val RESULT_SNAPSHOT = 0x4E45

    data class Snapshot(
        val processId: Int,
        val elapsedMs: Long,
        val cpuMs: Long,
        val active: Boolean,
        val suspended: Boolean,
        val slotCount: Int,
        val slotFrames: Long,
        val taskQueries: Long,
        val previewCaptures: Long,
    )

    fun register(context: Context, handler: Handler, snapshot: () -> Bundle) {
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION || !isOrderedBroadcast) return
                setResultExtras(snapshot())
                resultCode = RESULT_SNAPSHOT
            }
        }, IntentFilter(ACTION), TriggerBroadcastContract.CONTROL_PERMISSION, handler, Context.RECEIVER_EXPORTED)
    }

    fun query(context: Context, onResult: (Snapshot?) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var completed = false
        fun complete(value: Snapshot?) {
            if (completed) return
            completed = true
            onResult(value)
        }
        val timeout = Runnable { complete(null) }
        handler.postDelayed(timeout, 1_500L)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                handler.removeCallbacks(timeout)
                val data = getResultExtras(false)
                val value = if (resultCode == RESULT_SNAPSHOT && data?.getInt("version") == 1) {
                    Snapshot(data.getInt("pid"), data.getLong("elapsed_ms"), data.getLong("cpu_ms"),
                        data.getBoolean("active"), data.getBoolean("suspended"), data.getInt("slot_count"),
                        data.getLong("slot_frames"), data.getLong("task_queries"), data.getLong("preview_captures"))
                } else null
                complete(value)
            }
        }
        runCatching {
            context.sendOrderedBroadcast(Intent(ACTION).setPackage(TriggerBroadcastContract.SYSTEM_UI_PACKAGE)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND), null, receiver, handler, 0, null, null)
        }.onFailure {
            handler.removeCallbacks(timeout)
            complete(null)
        }
    }
}
