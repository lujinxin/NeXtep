package io.github.lujinxin.nextep.config

import android.content.Context
import io.github.lujinxin.nextep.safety.FeatureGate

class SettingsRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences("nextep_settings", Context.MODE_PRIVATE)

    data class TopBarSettings(
        val title: String,
        val contentMode: String,
        val showSeconds: Boolean,
        val frostStrength: Int,
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
        contentMode = TopContentMode.resolve(preferences.getString("top_content_mode", null), preferences.getString(KEY_TOP_TITLE, "").orEmpty()),
        showSeconds = preferences.getBoolean("top_show_seconds", false),
        frostStrength = preferences.getInt("frost_strength", 50).coerceIn(0, 100),
        manualAppOrder = preferences.getBoolean(KEY_MANUAL_APP_ORDER, false),
        appComponents = preferences.getString(KEY_TOP_APPS, null)
            ?.split(COMPONENT_SEPARATOR)
            ?.filter(String::isNotBlank)
            .orEmpty(),
    )

    private fun notifyTopContentChanged() {
        context.sendBroadcast(android.content.Intent(WorkspaceConfigContract.ACTION_CHANGED)
            .setPackage("com.android.systemui"))
    }

    fun setTopTitle(title: String) {
        preferences.edit()
            .putString(KEY_TOP_TITLE, title.trim().ifEmpty { DEFAULT_TOP_TITLE })
            .putString("top_content_mode", if (title.isBlank()) TopContentMode.ICON else TopContentMode.TEXT)
            .apply()
        notifyTopContentChanged()
    }

    fun topTitleDraft(): String =
        preferences.getString("top_title_draft", preferences.getString(KEY_TOP_TITLE, "")).orEmpty()

    fun setTopTitleDraft(value: String) {
        preferences.edit().putString("top_title_draft", value).apply()
    }

    fun setTopContentMode(mode: String) {
        preferences.edit().putString("top_content_mode", TopContentMode.resolve(mode, "")).apply()
        notifyTopContentChanged()
    }

    fun setTopShowSeconds(enabled: Boolean) {
        preferences.edit().putBoolean("top_show_seconds", enabled).apply()
        notifyTopContentChanged()
    }

    fun setFrostStrength(value: Int) {
        preferences.edit().putInt("frost_strength", value.coerceIn(0, 100)).apply()
        notifyTopContentChanged()
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
        const val DEFAULT_TOP_TITLE = ""
        const val COMPONENT_SEPARATOR = "\n"
        const val MAX_TOP_APPS = 36
    }
}
