package io.github.lujinxin.nextep.monitoring

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock

/** All measurements are battery/device values, never per-module energy attribution. */
internal data class BatterySample(
    val elapsedMs: Long,
    val levelPercent: Double?,
    val temperatureC: Double?,
    val voltageV: Double?,
    val currentMa: Double?,
    val status: Int,
    val plugged: Int?,
    val thermalStatus: Int?,
    val powerSave: Boolean?,
) {
    // This is net power at the battery. Charging current excludes loads supplied
    // directly by the charger, so it must never be labelled total device power.
    val batteryPowerW: Double? get() = currentMa?.let { current -> voltageV?.let { current * it / 1_000.0 } }

    val dischargePowerW: Double? get() = batteryPowerW?.takeIf {
        plugged == 0 && status == BatteryManager.BATTERY_STATUS_DISCHARGING && it < 0.0
    }?.let { -it }

    companion object {
        fun read(context: Context): BatterySample {
            val intent = runCatching {
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull()
            fun value(key: String): Int? = if (intent?.hasExtra(key) == true) intent.getIntExtra(key, 0) else null
            val manager = context.getSystemService(BatteryManager::class.java)
            val power = context.getSystemService(PowerManager::class.java)
            val current = runCatching { manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }
                .getOrNull()?.takeUnless { it == Int.MIN_VALUE }
            val level = value(BatteryManager.EXTRA_LEVEL)
            val scale = value(BatteryManager.EXTRA_SCALE)?.takeIf { it > 0 }
            return BatterySample(
                SystemClock.elapsedRealtime(),
                if (level != null && scale != null && level in 0..scale) level * 100.0 / scale else null,
                value(BatteryManager.EXTRA_TEMPERATURE)?.let { it / 10.0 },
                value(BatteryManager.EXTRA_VOLTAGE)?.takeIf { it > 0 }?.let { it / 1_000.0 },
                current?.let { it / 1_000.0 },
                value(BatteryManager.EXTRA_STATUS) ?: BatteryManager.BATTERY_STATUS_UNKNOWN,
                value(BatteryManager.EXTRA_PLUGGED),
                runCatching { power?.currentThermalStatus }.getOrNull(),
                runCatching { power?.isPowerSaveMode }.getOrNull(),
            )
        }
    }
}
