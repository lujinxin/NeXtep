package io.github.lujinxin.nextep.monitoring

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.HardwarePropertiesManager
import android.os.OutcomeReceiver
import android.os.PowerMonitor
import android.os.PowerMonitorReadings
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.health.SystemHealthManager
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.monitoring.WorkspaceMetric.*
import java.io.File
import java.util.concurrent.Executor

/** One visible monitoring session. All reads and asynchronous power callbacks use the same worker. */
internal class WorkspaceMonitorSampler(
    private val context: Context,
    private val selected: Set<WorkspaceMetric>,
    private val worker: Executor,
) {
    private var initialTemperature: Double? = null
    private var dischargeSum = 0.0
    private var dischargeSamples = 0L
    private var previousCpu: CpuTimes? = null
    private var previousRuntime: RuntimeSample? = null
    private var previousTraffic: TrafficSample? = null
    private val device = WorkspaceDeviceReadings(context)
    private val gpuPower = GpuPowerMonitor(context, worker)
    private var lastGpuTraceRequestMs = -5_000L
    private var tracedGpu: Double? = null
    private var tracedGpuAtMs = 0L
    private val oiface by lazy {
        runCatching {
            val type = Class.forName("com.oplus.oiface.OifaceManager")
            val manager = type.getMethod("getInstance", String::class.java).invoke(null, context.packageName)
            manager to type.getMethod("getGpuCurrentFreq")
        }.getOrNull()
    }

    fun read(slots: Bundle?): Map<WorkspaceMetric, String?> {
        val battery = if (selected.any { it in batteryMetrics }) {
            runCatching { BatterySample.read(context) }.getOrNull()
        } else null
        if (initialTemperature == null) initialTemperature = battery?.temperatureC
        battery?.dischargePowerW?.takeIf { AVERAGE_DISCHARGE in selected }?.let {
            dischargeSum += it
            dischargeSamples++
        }
        val runtime = if (selected.any { it in runtimeMetrics }) RuntimeSample(
            elapsedMs = SystemClock.elapsedRealtime(),
            hostCpuMs = Process.getElapsedCpuTime(),
            slotFrames = slots?.getLong("slot_frames") ?: 0,
            taskQueries = WorkspaceEnergyDiagnostics.taskQueries.get(),
            previewCaptures = WorkspaceEnergyDiagnostics.previewCaptures.get(),
        ) else null
        val previous = previousRuntime
        previousRuntime = runtime
        val traffic = if (UPLOAD_SPEED in selected || DOWNLOAD_SPEED in selected) {
            runCatching {
                TrafficSample(SystemClock.elapsedRealtime(), TrafficStats.getTotalTxBytes(), TrafficStats.getTotalRxBytes())
            }.getOrNull()
        } else null
        val priorTraffic = previousTraffic
        previousTraffic = traffic
        val seconds = if (runtime != null && previous != null) {
            ((runtime.elapsedMs - previous.elapsedMs) / 1_000.0).takeIf { it > 0 }
        } else null
        fun rate(value: Long?, old: Long?) =
            if (seconds != null && value != null && old != null && value >= old) (value - old) / seconds else null
        val locale = AppLanguage.locale(context)
        fun format(value: Double?, pattern: String) = value?.let { String.format(locale, pattern, it) }
        fun string(label: Int, vararg args: Any) = AppLanguage.string(context, label, *args)
        val noData by lazy { string(R.string.workspace_monitor_no_data) }
        val wifi by lazy { runCatching(device::wifi) }
        val storage by lazy { runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull() }
        fun wifiReading(reading: (WifiInfo) -> String?): String =
            wifi.fold(
                onSuccess = { info ->
                    if (info == null) string(R.string.workspace_monitor_wifi_not_connected) else reading(info) ?: noData
                },
                onFailure = { noData },
            )
        fun bytes(value: Long?, storageUnits: Boolean = false): String? {
            if (value == null || value < 0) return null
            val units = if (storageUnits) diskUnits else trafficUnits
            val divisor = if (storageUnits) 1_024 else 1_000
            var amount = value.toDouble()
            var unit = 0
            while (amount >= divisor && unit < units.lastIndex) {
                amount /= divisor
                unit++
            }
            return String.format(locale, if (unit == 0 || amount >= 100) "%.0f %s" else "%.1f %s", amount, units[unit])
        }
        fun duration(minutes: Long) = if (minutes >= 60) {
            string(R.string.workspace_monitor_duration_hours, minutes / 60, minutes % 60)
        } else {
            string(R.string.workspace_monitor_duration_minutes, minutes)
        }
        fun trafficRate(bytes: Long?, priorBytes: Long?): String? {
            if (traffic == null || priorTraffic == null || bytes == null || priorBytes == null ||
                priorBytes < 0 || bytes < priorBytes) return null
            val intervalMs = traffic.elapsedMs - priorTraffic.elapsedMs
            if (intervalMs <= 0) return null
            val bytesPerSecond = (bytes - priorBytes) * 1_000.0 / intervalMs
            fun scaled(divisor: Int, unit: String): String {
                val value = bytesPerSecond / divisor
                val digits = if (value >= 10 || value % 1.0 == 0.0) "%.0f" else "%.1f"
                return String.format(locale, digits, value) + unit
            }
            return when {
                bytesPerSecond >= 1_000_000_000 -> scaled(1_000_000_000, "GB/s")
                bytesPerSecond >= 1_000_000 -> scaled(1_000_000, "MB/s")
                bytesPerSecond >= 1_000 -> scaled(1_000, "KB/s")
                else -> format(bytesPerSecond, "%.0fB/s")
            }
        }

        return selected.associateWith { metric ->
            runCatching {
                when (metric) {
                    BATTERY_LEVEL -> format(battery?.levelPercent, "%.0f%%")
                    BATTERY_CYCLES -> battery?.cycleCount?.let { string(R.string.workspace_monitor_cycle_value, it) } ?: noData
                    BATTERY_TEMPERATURE -> format(battery?.temperatureC, "%.1f °C")
                    TEMPERATURE_CHANGE -> format(battery?.temperatureC?.let { temp -> initialTemperature?.let { temp - it } }, "%+.1f °C")
                    CHARGE_STATE -> battery?.status?.let(::chargeLabel)?.let { string(it) }
                    BATTERY_VOLTAGE -> format(battery?.voltageV, "%.2f V")
                    BATTERY_CURRENT -> format(battery?.currentMa, "%+.0f mA")
                    BATTERY_POWER -> format(battery?.batteryPowerW, "%+.2f W")
                    AVERAGE_DISCHARGE -> format((dischargeSum / dischargeSamples).takeIf { dischargeSamples >= 2 }, "%.2f W")
                    THERMAL_STATUS -> battery?.thermalStatus?.let(thermalLabels::getOrNull)?.let { string(it) }
                    POWER_SAVE -> battery?.powerSave?.let { string(if (it) R.string.energy_enabled else R.string.energy_disabled) }
                    NETWORK -> networkLabel()?.let { string(it) }
                    WIFI_SIGNAL -> wifiReading { format(it.rssi.takeIf { rssi -> rssi in -126..-1 }?.toDouble(), "%.0f dBm") }
                    WIFI_CHANNEL -> wifiReading {
                        val frequency = it.frequency
                        val band = when (frequency) {
                            in 2_400..2_500 -> "2.4 GHz"
                            in 4_900..5_895 -> "5 GHz"
                            in 5_925..7_125 -> "6 GHz"
                            in 57_000..71_000 -> "60 GHz"
                            else -> null
                        }
                        val channel = ScanResult.convertFrequencyMhzToChannelIfSupported(frequency)
                        when {
                            band != null && channel > 0 -> string(R.string.workspace_monitor_channel_value, band, channel)
                            band != null -> band
                            frequency > 0 -> string(R.string.workspace_monitor_frequency_value, frequency)
                            else -> null
                        }
                    }
                    WIFI_STANDARD -> wifiReading {
                        when (it.wifiStandard) {
                            ScanResult.WIFI_STANDARD_LEGACY -> string(R.string.workspace_monitor_wifi_legacy)
                            ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4"
                            ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5"
                            ScanResult.WIFI_STANDARD_11AX -> if (it.frequency in 5_925..7_125) "Wi-Fi 6E" else "Wi-Fi 6"
                            ScanResult.WIFI_STANDARD_11AD -> "802.11ad"
                            ScanResult.WIFI_STANDARD_11BE -> "Wi-Fi 7"
                            else -> null
                        }
                    }
                    WIFI_TX_LINK -> wifiReading { format(it.txLinkSpeedMbps.takeIf { speed -> speed > 0 }?.toDouble(), "%.0f Mbps") }
                    WIFI_RX_LINK -> wifiReading { format(it.rxLinkSpeedMbps.takeIf { speed -> speed > 0 }?.toDouble(), "%.0f Mbps") }
                    CELL_SIGNAL -> format(runCatching(device::cellSignal).getOrNull()?.toDouble(), "%.0f dBm") ?: noData
                    UPLOAD_SPEED -> trafficRate(traffic?.sentBytes, priorTraffic?.sentBytes)
                    DOWNLOAD_SPEED -> trafficRate(traffic?.receivedBytes, priorTraffic?.receivedBytes)
                    TOTAL_UPLOAD -> bytes(TrafficStats.getTotalTxBytes()) ?: noData
                    TOTAL_DOWNLOAD -> bytes(TrafficStats.getTotalRxBytes()) ?: noData
                    TOTAL_MOBILE -> {
                        val tx = TrafficStats.getMobileTxBytes()
                        val rx = TrafficStats.getMobileRxBytes()
                        if (tx < 0 || rx < 0) noData else bytes(tx + rx) ?: noData
                    }
                    CPU_LOAD -> format(cpuLoad(), "%.0f%%")
                    CPU_FREQUENCY -> format(cpuFrequency(), "%.0f MHz")
                    CPU_TEMPERATURE -> format(device.temperature(HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU), "%.1f °C") ?: noData
                    GPU_LOAD -> format(gpuLoad(), "%.0f%%")
                    GPU_FREQUENCY -> format(gpuFrequency(), "%.0f MHz")
                    GPU_TEMPERATURE -> format(device.temperature(HardwarePropertiesManager.DEVICE_TEMPERATURE_GPU), "%.1f °C") ?: noData
                    GPU_POWER -> format(gpuPower.read(), "≈%.2f W")
                    MEMORY -> format(memoryLoad(), "%.0f%%")
                    FREE_STORAGE -> bytes(storage?.availableBytes, storageUnits = true) ?: noData
                    STORAGE_USAGE -> format(storage?.let {
                        if (it.totalBytes > 0 && it.freeBytes in 0..it.totalBytes) {
                            (it.totalBytes - it.freeBytes) * 100.0 / it.totalBytes
                        } else null
                    }, "%.0f%%") ?: noData
                    UPTIME -> duration(SystemClock.elapsedRealtime() / 60_000L)
                    BRIGHTNESS -> format(runCatching(device::brightness).getOrNull(), "%.0f%%") ?: noData
                    AUTO_BRIGHTNESS -> runCatching(device::autoBrightness).getOrNull()?.let {
                        string(if (it) R.string.energy_enabled else R.string.energy_disabled)
                    } ?: noData
                    WORKSPACE_STATE -> string(R.string.energy_enabled)
                    SLOTS -> slots?.getInt("slot_count")?.toString()
                    SLOT_FRAMES -> format(rate(runtime?.slotFrames, previous?.slotFrames), "%.1f/s")
                    TASK_QUERIES -> format(rate(runtime?.taskQueries, previous?.taskQueries), "%.1f/s")
                    PREVIEW_CAPTURES -> format(rate(runtime?.previewCaptures, previous?.previewCaptures), "%.1f/s")
                    HOST_CPU -> format(rate(runtime?.hostCpuMs, previous?.hostCpuMs)?.div(10), "%.1f%%")
                }
            }.getOrNull()
        }
    }

    private fun chargeLabel(status: Int): Int? = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> R.string.energy_charging
        BatteryManager.BATTERY_STATUS_DISCHARGING -> R.string.energy_discharging
        BatteryManager.BATTERY_STATUS_FULL -> R.string.energy_full
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> R.string.energy_not_charging
        else -> null
    }

    private fun networkLabel(): Int? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return R.string.workspace_monitor_network_offline
        val caps = manager.getNetworkCapabilities(network) ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> R.string.workspace_monitor_network_vpn
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> R.string.workspace_monitor_network_wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> R.string.workspace_monitor_network_mobile
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> R.string.workspace_monitor_network_ethernet
            else -> R.string.workspace_monitor_network_other
        }
    }

    private fun cpuLoad(): Double? {
        val fields = runCatching { File("/proc/stat").useLines { it.firstOrNull() } }.getOrNull()
            ?.trim()?.split(whitespace) ?: return null
        if (fields.firstOrNull() != "cpu") return null
        val times = fields.drop(1).take(8).map { it.toLongOrNull() ?: return null }
        if (times.size < 4) return null
        val sample = CpuTimes(times.sum(), times[3] + (times.getOrNull(4) ?: 0))
        val previous = previousCpu
        previousCpu = sample
        if (previous == null || sample.total <= previous.total || sample.idle < previous.idle) return null
        val total = sample.total - previous.total
        val idle = sample.idle - previous.idle
        return if (idle <= total) (total - idle) * 100.0 / total else null
    }

    private fun cpuFrequency(): Double? = File("/sys/devices/system/cpu/cpufreq").listFiles()
        ?.filter { it.name.startsWith("policy") }
        ?.mapNotNull { policy ->
            readNumber(File(policy, "cpuinfo_cur_freq").path)?.takeIf { it > 0 }
                ?: readNumber(File(policy, "scaling_cur_freq").path)?.takeIf { it > 0 }
        }
        ?.maxOrNull()?.div(1_000)

    private fun gpuLoad(): Double? {
        readText("$KGSL/gpu_busy_percentage")?.removeSuffix("%")?.trim()?.toDoubleOrNull()
            ?.takeIf { it in 0.0..100.0 }?.let { return it }
        val fields = readText("$KGSL/gpubusy")?.split(whitespace) ?: return null
        val busy = fields.getOrNull(0)?.toDoubleOrNull() ?: return null
        val elapsed = fields.getOrNull(1)?.toDoubleOrNull() ?: return null
        return if (elapsed > 0 && busy in 0.0..elapsed) busy * 100.0 / elapsed else null
    }

    private fun gpuFrequency(): Double? {
        fun valid(value: Double?) = value?.takeIf { it in 1.0..3_000.0 }
        valid(readNumber("$KGSL/gpuclk")?.div(1_000_000))?.let { return it }
        valid(readNumber("$KGSL/clock_mhz"))?.let { return it }
        valid(readNumber("/sys/kernel/gpu/gpu_clock"))?.let { return it }
        valid(readNumber("$KGSL/devfreq/cur_freq")?.div(1_000_000))?.let { return it }
        oiface?.let { (manager, method) ->
            val raw = (runCatching { method.invoke(manager) }.getOrNull() as? Number)?.toDouble()
            if (raw != null) {
                // Oiface implementations can expose the clock in different units.
                (valid(raw) ?: valid(raw / 1_000) ?: valid(raw / 1_000_000))?.let { return it }
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastGpuTraceRequestMs >= 4_000L) {
            lastGpuTraceRequestMs = now
            WorkspaceGpuTrace.query(context) { mhz ->
                if (mhz != null) worker.execute {
                    tracedGpu = mhz
                    tracedGpuAtMs = SystemClock.elapsedRealtime()
                }
            }
        }
        return tracedGpu?.takeIf { now - tracedGpuAtMs <= 10_000L }
    }

    private fun memoryLoad(): Double? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return if (info.totalMem > 0 && info.availMem in 0..info.totalMem) {
            (info.totalMem - info.availMem) * 100.0 / info.totalMem
        } else null
    }

    private data class CpuTimes(val total: Long, val idle: Long)
    private data class TrafficSample(val elapsedMs: Long, val sentBytes: Long, val receivedBytes: Long)
    private data class RuntimeSample(
        val elapsedMs: Long,
        val hostCpuMs: Long,
        val slotFrames: Long,
        val taskQueries: Long,
        val previewCaptures: Long,
    )

    companion object {
        private const val KGSL = "/sys/class/kgsl/kgsl-3d0"
        private val trafficUnits = arrayOf("B", "KB", "MB", "GB", "TB")
        private val diskUnits = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
        private val whitespace = Regex("\\s+")
        private val batteryMetrics = setOf(
            BATTERY_LEVEL, BATTERY_CYCLES, BATTERY_TEMPERATURE, TEMPERATURE_CHANGE, CHARGE_STATE,
            BATTERY_VOLTAGE, BATTERY_CURRENT, BATTERY_POWER, AVERAGE_DISCHARGE, THERMAL_STATUS, POWER_SAVE,
        )
        private val runtimeMetrics = setOf(SLOT_FRAMES, TASK_QUERIES, PREVIEW_CAPTURES, HOST_CPU)
        private val thermalLabels = intArrayOf(R.string.energy_thermal_none, R.string.energy_thermal_light,
            R.string.energy_thermal_moderate, R.string.energy_thermal_severe, R.string.energy_thermal_critical,
            R.string.energy_thermal_emergency, R.string.energy_thermal_shutdown)

        private fun readText(path: String) = runCatching { File(path).readText().trim() }.getOrNull()
        private fun readNumber(path: String) = readText(path)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    }
}

private class GpuPowerMonitor(private val context: Context, private val worker: Executor) {
    private var monitor: PowerMonitor? = null
    private var enumerated = false
    private var pending = false
    private var previousEnergy: Long? = null
    private var previousTimestamp = 0L
    private var watts: Double? = null

    fun read(): Double? {
        if (!pending) runCatching { request() }.onFailure { pending = false; watts = null }
        return watts?.takeIf { SystemClock.elapsedRealtime() - previousTimestamp <= 15_000L }
    }

    private fun request() {
        if (enumerated && monitor == null) return
        val manager = context.getSystemService(SystemHealthManager::class.java) ?: return
        pending = true
        if (!enumerated) {
            manager.getSupportedPowerMonitors(worker) { monitors ->
                // The named subsystem is OEM-defined. An arbitrary rail may be shared.
                monitor = monitors.singleOrNull {
                    it.type == PowerMonitor.POWER_MONITOR_TYPE_CONSUMER && it.name.equals("GPU", ignoreCase = true)
                }
                enumerated = true
                pending = false
            }
            return
        }
        val gpu = checkNotNull(monitor)
        manager.getPowerMonitorReadings(listOf(gpu), worker, object : OutcomeReceiver<PowerMonitorReadings, RuntimeException> {
            override fun onResult(result: PowerMonitorReadings) {
                pending = false
                val energy = result.getConsumedEnergy(gpu)
                val timestamp = result.getTimestampMillis(gpu)
                if (energy < 0) {
                    previousEnergy = null
                    watts = null
                    return
                }
                if (previousEnergy != null && timestamp <= previousTimestamp) return
                val before = previousEnergy
                watts = if (before != null && energy >= before) {
                    (energy - before).toDouble() / (timestamp - previousTimestamp) / 1_000.0
                } else null
                previousEnergy = energy
                previousTimestamp = timestamp
            }

            override fun onError(error: RuntimeException) {
                pending = false
                previousEnergy = null
                watts = null
            }
        })
    }
}
