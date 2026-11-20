package io.github.lujinxin.nextep.monitoring

import androidx.annotation.StringRes
import io.github.lujinxin.nextep.R

internal enum class WorkspaceMetric(val id: String, @StringRes val label: Int, @StringRes val compactLabel: Int = label) {
    BATTERY_LEVEL("battery_level", R.string.energy_battery_level),
    BATTERY_CYCLES("battery_cycles", R.string.workspace_monitor_battery_cycles, R.string.workspace_monitor_short_battery_cycles),
    BATTERY_TEMPERATURE("battery_temperature", R.string.energy_battery_temperature, R.string.workspace_monitor_short_temperature),
    TEMPERATURE_CHANGE("temperature_change", R.string.energy_temperature_change),
    CHARGE_STATE("charge_state", R.string.energy_charge_state),
    BATTERY_VOLTAGE("battery_voltage", R.string.energy_voltage, R.string.workspace_monitor_short_voltage),
    BATTERY_CURRENT("battery_current", R.string.energy_current, R.string.workspace_monitor_short_current),
    BATTERY_POWER("battery_power", R.string.energy_battery_power),
    AVERAGE_DISCHARGE("average_discharge", R.string.energy_average_discharge),
    THERMAL_STATUS("thermal_status", R.string.energy_thermal_status),
    POWER_SAVE("power_save", R.string.energy_power_save),
    NETWORK("network", R.string.workspace_monitor_network),
    WIFI_SIGNAL("wifi_signal", R.string.workspace_monitor_wifi_signal),
    WIFI_CHANNEL("wifi_channel", R.string.workspace_monitor_wifi_channel, R.string.workspace_monitor_short_wifi_channel),
    WIFI_STANDARD("wifi_standard", R.string.workspace_monitor_wifi_standard),
    WIFI_TX_LINK("wifi_tx_link", R.string.workspace_monitor_wifi_tx_link, R.string.workspace_monitor_short_wifi_tx_link),
    WIFI_RX_LINK("wifi_rx_link", R.string.workspace_monitor_wifi_rx_link, R.string.workspace_monitor_short_wifi_rx_link),
    CELL_SIGNAL("cell_signal", R.string.workspace_monitor_cell_signal, R.string.workspace_monitor_short_cell_signal),
    UPLOAD_SPEED("upload_speed", R.string.workspace_monitor_upload_speed, R.string.workspace_monitor_short_upload),
    DOWNLOAD_SPEED("download_speed", R.string.workspace_monitor_download_speed, R.string.workspace_monitor_short_download),
    TOTAL_UPLOAD("total_upload", R.string.workspace_monitor_total_upload, R.string.workspace_monitor_short_total_upload),
    TOTAL_DOWNLOAD("total_download", R.string.workspace_monitor_total_download, R.string.workspace_monitor_short_total_download),
    TOTAL_MOBILE("total_mobile", R.string.workspace_monitor_total_mobile, R.string.workspace_monitor_short_total_mobile),
    CPU_LOAD("cpu_load", R.string.workspace_monitor_cpu_load, R.string.workspace_monitor_short_cpu_load),
    CPU_FREQUENCY("cpu_frequency", R.string.workspace_monitor_cpu_frequency, R.string.workspace_monitor_short_cpu_frequency),
    CPU_TEMPERATURE("cpu_temperature", R.string.workspace_monitor_cpu_temperature, R.string.workspace_monitor_short_cpu_temperature),
    GPU_LOAD("gpu_load", R.string.workspace_monitor_gpu_load, R.string.workspace_monitor_short_gpu_load),
    GPU_FREQUENCY("gpu_frequency", R.string.workspace_monitor_gpu_frequency, R.string.workspace_monitor_short_gpu_frequency),
    GPU_TEMPERATURE("gpu_temperature", R.string.workspace_monitor_gpu_temperature, R.string.workspace_monitor_short_gpu_temperature),
    GPU_POWER("gpu_power", R.string.workspace_monitor_gpu_power),
    MEMORY("memory", R.string.workspace_monitor_memory, R.string.workspace_monitor_short_memory),
    FREE_STORAGE("free_storage", R.string.workspace_monitor_free_storage),
    STORAGE_USAGE("storage_usage", R.string.workspace_monitor_storage_usage),
    UPTIME("uptime", R.string.workspace_monitor_uptime),
    BRIGHTNESS("brightness", R.string.workspace_monitor_brightness),
    AUTO_BRIGHTNESS("auto_brightness", R.string.workspace_monitor_auto_brightness),
    WORKSPACE_STATE("workspace_state", R.string.energy_workspace_state),
    SLOTS("slots", R.string.energy_slots),
    SLOT_FRAMES("slot_frames", R.string.energy_slot_frames),
    TASK_QUERIES("task_queries", R.string.energy_task_queries),
    PREVIEW_CAPTURES("preview_captures", R.string.energy_preview_captures),
    HOST_CPU("host_cpu", R.string.energy_host_cpu);

    companion object {
        const val COLUMN_COUNT = 3
        private const val SLOT_COUNT = 9
        private val byId = entries.associateBy(WorkspaceMetric::id)
        val defaultSlots: List<String> = listOf(
            UPLOAD_SPEED, MEMORY, BATTERY_TEMPERATURE,
            DOWNLOAD_SPEED, CPU_FREQUENCY, CPU_LOAD,
            FREE_STORAGE, GPU_FREQUENCY, GPU_LOAD,
        ).map(WorkspaceMetric::id)

        private val retiredDefaultSlots = setOf(
            listOf(
                UPLOAD_SPEED.id, MEMORY.id, BATTERY_TEMPERATURE.id,
                DOWNLOAD_SPEED.id, CPU_FREQUENCY.id, BATTERY_VOLTAGE.id,
                "display_refresh", GPU_FREQUENCY.id, BATTERY_CURRENT.id,
            ),
            listOf(
                UPLOAD_SPEED.id, MEMORY.id, BATTERY_TEMPERATURE.id,
                DOWNLOAD_SPEED.id, CPU_FREQUENCY.id, CPU_LOAD.id,
                "device_power", GPU_FREQUENCY.id, GPU_LOAD.id,
            ),
            listOf(
                UPLOAD_SPEED.id, MEMORY.id, BATTERY_TEMPERATURE.id,
                DOWNLOAD_SPEED.id, CPU_FREQUENCY.id, CPU_LOAD.id,
                "battery_remaining", GPU_FREQUENCY.id, GPU_LOAD.id,
            ),
        )

        fun fromId(id: String): WorkspaceMetric? = byId[id]

        fun slots(ids: List<String>): List<String> {
            if (ids in retiredDefaultSlots) return defaultSlots
            val used = mutableSetOf<String>()
            return List(SLOT_COUNT) { index ->
                ids.getOrNull(index)?.takeIf { it in byId && used.add(it) }.orEmpty()
            }
        }
    }
}
