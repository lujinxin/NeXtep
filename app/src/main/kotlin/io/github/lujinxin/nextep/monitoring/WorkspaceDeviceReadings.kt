package io.github.lujinxin.nextep.monitoring

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.HardwarePropertiesManager
import android.provider.Settings
import android.telephony.CellInfo
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.Display

/** Device services accessed from the SystemUI host, only for selected metrics. */
internal class WorkspaceDeviceReadings(private val context: Context) {
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun wifi(): WifiInfo? {
        val active = context.getSystemService(ConnectivityManager::class.java)?.let { manager ->
            manager.activeNetwork?.let(manager::getNetworkCapabilities)?.transportInfo as? WifiInfo
        }
        if (active != null) return active

        // The default network may be a VPN layered on top of the Wi-Fi link.
        return context.getSystemService(WifiManager::class.java)?.connectionInfo
            ?.takeIf { it.supplicantState == SupplicantState.COMPLETED }
    }

    @SuppressLint("MissingPermission")
    fun cellSignal(): Int? {
        val id = SubscriptionManager.getDefaultDataSubscriptionId()
        if (!SubscriptionManager.isValidSubscriptionId(id)) return null
        val manager = context.getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(id) ?: return null
        val signals = manager.signalStrength?.cellSignalStrengths.orEmpty()
            .filter { it.dbm < 0 && it.dbm != CellInfo.UNAVAILABLE }
        val primary = when (runCatching { manager.dataNetworkType }.getOrNull()) {
            TelephonyManager.NETWORK_TYPE_NR -> signals.firstOrNull { it is CellSignalStrengthNr }
            TelephonyManager.NETWORK_TYPE_LTE -> signals.firstOrNull { it is CellSignalStrengthLte }
            else -> null
        }
        return (primary ?: signals.firstOrNull())?.dbm
    }

    @SuppressLint("MissingPermission")
    fun temperature(type: Int): Double? = runCatching {
        context.getSystemService(HardwarePropertiesManager::class.java)
            ?.getDeviceTemperatures(type, HardwarePropertiesManager.TEMPERATURE_CURRENT)
            ?.filter { it.isFinite() && it != HardwarePropertiesManager.UNDEFINED_TEMPERATURE }
            ?.maxOrNull()?.toDouble()
    }.getOrNull()

    fun brightness(): Double? {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return null
        val info = brightnessInfoMethod?.invoke(display) ?: return null
        val fields = info.javaClass
        val level = fields.getField("brightness").getFloat(info)
        val minimum = fields.getField("brightnessMinimum").getFloat(info)
        val maximum = fields.getField("brightnessMaximum").getFloat(info)
        if (!level.isFinite() || !minimum.isFinite() || !maximum.isFinite() ||
            level < 0f || maximum <= minimum) return null
        return ((level - minimum).toDouble() / (maximum - minimum)).coerceIn(0.0, 1.0) * 100.0
    }

    fun autoBrightness(): Boolean? = when (Settings.System.getInt(
        context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1,
    )) {
        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC -> true
        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL -> false
        else -> null
    }

    private companion object {
        // SystemUI has access to this framework API; the public SDK omits it.
        val brightnessInfoMethod by lazy {
            runCatching { Display::class.java.getMethod("getBrightnessInfo") }.getOrNull()
        }
    }
}
