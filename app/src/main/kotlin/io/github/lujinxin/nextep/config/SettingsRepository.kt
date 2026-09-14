package io.github.lujinxin.nextep.config

import android.content.Context
import io.github.lujinxin.nextep.safety.FeatureGate

class SettingsRepository(context: Context) {
    private val preferences = context.getSharedPreferences("nextep_settings", Context.MODE_PRIVATE)

    data class TopBarSettings(
        val title: String,
        val manualAppOrder: Boolean,
        val appComponents: List<String>,
    )

    fun isEnabled(feature: FeatureGate): Boolean =
        preferences.getBoolean(feature.name, feature.defaultEnabled)

    fun setEnabled(feature: FeatureGate, enabled: Boolean) {
        preferences.edit().putBoolean(feature.name, enabled).apply()
    }

    fun topBarSettings(): TopBarSettings = TopBarSettings(
        title = preferences.getString(KEY_TOP_TITLE, DEFAULT_TOP_TITLE)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: DEFAULT_TOP_TITLE,
        manualAppOrder = preferences.getBoolean(KEY_MANUAL_APP_ORDER, false),
        appComponents = preferences.getString(KEY_TOP_APPS, null)
            ?.split(COMPONENT_SEPARATOR)
            ?.filter(String::isNotBlank)
            .orEmpty(),
    )

    fun setTopTitle(title: String) {
        preferences.edit()
            .putString(KEY_TOP_TITLE, title.trim().ifEmpty { DEFAULT_TOP_TITLE })
            .apply()
    }

    fun setTopApps(components: List<String>) {
        preferences.edit()
            .putString(
                KEY_TOP_APPS,
                components.distinct().take(MAX_TOP_APPS).joinToString(COMPONENT_SEPARATOR),
            )
            .apply()
    }

    fun setManualAppOrder(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_MANUAL_APP_ORDER, enabled).apply()
    }

    private companion object {
        const val KEY_TOP_TITLE = "top_title"
        const val KEY_TOP_APPS = "top_apps"
        const val KEY_MANUAL_APP_ORDER = "manual_app_order"
        const val DEFAULT_TOP_TITLE = "NeXtep"
        const val COMPONENT_SEPARATOR = "\n"
        const val MAX_TOP_APPS = 36
    }
}
