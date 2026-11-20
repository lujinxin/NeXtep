package io.github.lujinxin.nextep.monitoring

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A bounded, read-only Perfetto query from SystemUI to the existing system_server host. */
internal object WorkspaceGpuTrace {
    private const val ACTION = "io.github.lujinxin.nextep.action.QUERY_GPU_CLOCK"
    private const val STATUS_BAR_PERMISSION = "android.permission.STATUS_BAR_SERVICE"
    private const val RESULT_CLOCK = 0x4E47
    private const val EXTRA_KHZ = "khz"
    private const val MIN_INTERVAL_MS = 4_000L
    private const val PROCESS_TIMEOUT_MS = 2_500L
    private const val MAX_TRACE_BYTES = 64 * 1024
    private val registered = AtomicBoolean(false)
    private val loggedFailure = AtomicBoolean(false)
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val worker by lazy {
        Executors.newSingleThreadExecutor { Thread(it, "NeXtep-gpu-trace") }
    }

    @Suppress("DEPRECATION")
    fun register(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        runCatching {
            val systemUiUid = context.packageManager.getApplicationInfo(TriggerBroadcastContract.SYSTEM_UI_PACKAGE, 0).uid
            val receiver = object : BroadcastReceiver() {
                private var inFlight = false
                private var nextAllowedAt = 0L

                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != ACTION || !isOrderedBroadcast || sentFromUid != systemUiUid) return
                    val now = SystemClock.elapsedRealtime()
                    if (inFlight || now < nextAllowedAt) return
                    nextAllowedAt = now + MIN_INTERVAL_MS
                    inFlight = true
                    val result = goAsync()
                    worker.execute {
                        try {
                            readClockKhz()?.let { khz ->
                                result.setResultCode(RESULT_CLOCK)
                                result.setResultExtras(Bundle().apply { putLong(EXTRA_KHZ, khz) })
                            }
                        } finally {
                            result.finish()
                            handler.post { inFlight = false }
                        }
                    }
                }
            }
            context.registerReceiver(receiver, IntentFilter(ACTION), STATUS_BAR_PERMISSION, handler, Context.RECEIVER_EXPORTED)
        }.onFailure {
            registered.set(false)
            NeXtepLog.warn("workspace_gpu_trace", "GPU clock receiver unavailable", it)
        }
    }

    fun query(context: Context, onResult: (Double?) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val khz = if (resultCode == RESULT_CLOCK) getResultExtras(false)?.getLong(EXTRA_KHZ, -1) else null
                onResult(khz?.takeIf { it in 0L..3_000_000L }?.div(1_000.0))
            }
        }
        runCatching {
            val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true)
            context.sendOrderedBroadcast(
                Intent(ACTION).setPackage("android").addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY),
                null, options.toBundle(), receiver, handler, 0, null, null,
            )
        }.onFailure { onResult(null) }
    }

    private fun readClockKhz(): Long? = runCatching {
        val process = ProcessBuilder("/system/bin/perfetto", "--txt", "-c", "-", "-o", "-")
            .redirectError(File("/dev/null")).start()
        val timeout = Runnable { runCatching { process.destroyForcibly() } }
        handler.postDelayed(timeout, PROCESS_TIMEOUT_MS)
        try {
            process.outputStream.bufferedWriter().use { it.write(CONFIG) }
            val trace = process.inputStream.use { it.readNBytes(MAX_TRACE_BYTES + 1) }
            if (trace.size <= MAX_TRACE_BYTES) GpuClockTraceParser.latestKhz(trace) else null
        } finally {
            handler.removeCallbacks(timeout)
            process.destroy()
        }
    }.onFailure {
        if (loggedFailure.compareAndSet(false, true)) NeXtepLog.warn("workspace_gpu_trace", "GPU clock tracing failed", it)
    }.getOrNull()

    private const val CONFIG = """
        buffers { size_kb: 64 fill_policy: RING_BUFFER }
        duration_ms: 850
        data_sources {
          config { name: "linux.ftrace" ftrace_config { ftrace_events: "power/gpu_frequency" } }
        }
    """
}

private object GpuClockTraceParser {
    // Perfetto Trace.packet(1) -> TracePacket.ftrace_events(1) -> FtraceEventBundle.event(2).
    private const val GPU_FREQUENCY_EVENT = 332

    fun latestKhz(trace: ByteArray): Long? {
        val wire = Wire(trace)
        var timestamp = -1L
        var frequency: Long? = null
        wire.fields(0, trace.size) packet@{ field, _, packetStart, packetEnd ->
            if (field != 1 || packetStart == packetEnd) return@packet
            wire.fields(packetStart, packetEnd) bundle@{ type, _, bundleStart, bundleEnd ->
                if (type != 1 || bundleStart == bundleEnd) return@bundle
                wire.fields(bundleStart, bundleEnd) event@{ field, _, eventStart, eventEnd ->
                    if (field != 2 || eventStart == eventEnd) return@event
                    var eventTime = -1L
                    var clock: Long? = null
                    wire.fields(eventStart, eventEnd) { part, value, start, end ->
                        if (part == 1 && start == end) eventTime = value
                        if (part == GPU_FREQUENCY_EVENT && start != end) {
                            var device = 0L
                            var state: Long? = null
                            wire.fields(start, end) { metric, raw, _, _ ->
                                if (metric == 1) device = raw
                                if (metric == 2) state = raw
                            }
                            if (device == 0L) clock = state?.takeIf { it in 0L..3_000_000L }
                        }
                    }
                    if (clock != null && eventTime > timestamp) {
                        timestamp = eventTime
                        frequency = clock
                    }
                }
            }
        }
        return frequency
    }

    private class Wire(private val bytes: ByteArray) {
        fun fields(start: Int, end: Int, visit: (Int, Long, Int, Int) -> Unit) {
            var index = start
            fun varint(): Long {
                var value = 0L
                for (shift in 0..63 step 7) {
                    require(index < end)
                    val b = bytes[index++].toInt() and 255
                    value = value or ((b and 127).toLong() shl shift)
                    if (b and 128 == 0) return value
                }
                error("Invalid Perfetto varint")
            }
            while (index < end) {
                val tag = varint().toInt()
                val field = tag ushr 3
                when (tag and 7) {
                    0 -> {
                        val value = varint()
                        visit(field, value, index, index)
                    }
                    2 -> {
                        val size = varint()
                        require(size in 0L..(end - index).toLong())
                        val after = index + size.toInt()
                        visit(field, 0L, index, after)
                        index = after
                    }
                    1 -> index += 8
                    5 -> index += 4
                    else -> error("Invalid Perfetto field")
                }
                require(index <= end)
            }
        }
    }
}
