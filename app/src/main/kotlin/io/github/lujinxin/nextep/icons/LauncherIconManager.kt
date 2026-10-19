package io.github.lujinxin.nextep.icons

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.lujinxin.nextep.logging.NeXtepLog
import java.time.Duration
import java.time.Instant

/** Local launcher preferences are deliberately separate from workspace settings. */
internal object LauncherIconManager {
    const val PREFERENCES = "launcher_icon"
    const val AUTOMATIC = "auto"
    const val ACTION_REFRESH = "io.github.lujinxin.nextep.action.REFRESH_LAUNCHER_ICON"
    private const val KEY_CHOICE = "choice"
    private const val KEY_STARTED = "first_open_completed"
    private const val KEY_APPLIED = "applied_icon"

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun selection(context: Context): String {
        val saved = preferences(context).getString(KEY_CHOICE, AUTOMATIC)
        return LauncherIconStyle.fromKey(saved)?.key ?: AUTOMATIC
    }

    fun currentIcon(context: Context): LauncherIconStyle =
        LauncherIconStyle.entries.firstOrNull { isEnabled(context, it) } ?: LauncherIconStyle.DEFAULT

    fun seasonalIcon(): LauncherIconStyle = runCatching {
        SolarTermIconCalendar.resolve(Instant.now()).style
    }.getOrDefault(LauncherIconStyle.DEFAULT)

    @Synchronized
    fun onAppOpened(context: Context) {
        runCatching {
            val preferences = preferences(context)
            // Receivers never set this flag: installation alone keeps the default icon.
            if (!preferences.getBoolean(KEY_STARTED, false)) {
                check(preferences.edit().putBoolean(KEY_STARTED, true).commit())
            }
            update(context)
        }.onFailure { handleFailure(context, it) }
    }

    @Synchronized
    fun refresh(context: Context) {
        runCatching { update(context) }.onFailure { handleFailure(context, it) }
    }

    @Synchronized
    fun select(context: Context, choice: String): Boolean {
        require(choice == AUTOMATIC || LauncherIconStyle.fromKey(choice) != null)
        return runCatching {
            // Persist intent before touching components, including when choosing the
            // currently displayed season: that explicit choice must disable automation.
            check(preferences(context).edit()
                .putString(KEY_CHOICE, choice)
                .putBoolean(KEY_STARTED, true)
                .commit())
            update(context)
        }.onFailure { handleFailure(context, it) }.isSuccess
    }

    private fun update(context: Context) {
        if (!preferences(context).getBoolean(KEY_STARTED, false)) return
        val manual = LauncherIconStyle.fromKey(selection(context))
        if (manual != null) {
            cancelAlarm(context)
            applyIcon(context, manual)
            return
        }
        val schedule = SolarTermIconCalendar.resolve(Instant.now())
        applyIcon(context, schedule.style)
        scheduleAlarm(context, schedule.nextChange)
    }

    private fun applyIcon(context: Context, selected: LauncherIconStyle) {
        val changes = LauncherIconStyle.entries.mapNotNull { style ->
            val enabled = style == selected
            if (isEnabled(context, style) == enabled) null else PackageManager.ComponentEnabledSetting(
                component(context, style),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        if (changes.isNotEmpty()) {
            // API 33+ updates all aliases atomically. The real activity stays enabled.
            context.packageManager.setComponentEnabledSettings(changes)
        }
        val preferences = preferences(context)
        if (preferences.getString(KEY_APPLIED, null) != selected.key) {
            preferences.edit().putString(KEY_APPLIED, selected.key).apply()
        }
    }

    private fun isEnabled(context: Context, style: LauncherIconStyle): Boolean =
        when (context.packageManager.getComponentEnabledSetting(component(context, style))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> style == LauncherIconStyle.DEFAULT
            else -> false
        }

    private fun component(context: Context, style: LauncherIconStyle) =
        ComponentName(context.packageName, "${context.packageName}.icons.${style.aliasName}")

    private fun alarmIntent(context: Context, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context, 0, Intent(context, LauncherIconReceiver::class.java).setAction(ACTION_REFRESH),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun scheduleAlarm(context: Context, instant: Instant) {
        // A seasonal decoration needs no exact-alarm permission or persistent service.
        checkNotNull(context.getSystemService(AlarmManager::class.java)).setWindow(
            AlarmManager.RTC, instant.toEpochMilli(), Duration.ofMinutes(30).toMillis(),
            checkNotNull(alarmIntent(context, PendingIntent.FLAG_UPDATE_CURRENT)),
        )
    }

    private fun cancelAlarm(context: Context) {
        val pending = alarmIntent(context, PendingIntent.FLAG_NO_CREATE) ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(pending)
        pending.cancel()
    }

    private fun handleFailure(context: Context, error: Throwable) {
        NeXtepLog.warn("launcher_icon", "Unable to update launcher icon", error)
        // A failed automatic update can retry; a saved manual choice remains fixed.
        if (preferences(context).getBoolean(KEY_STARTED, false) && selection(context) == AUTOMATIC) {
            runCatching { scheduleAlarm(context, Instant.now().plus(Duration.ofHours(1))) }
                .onFailure { NeXtepLog.warn("launcher_icon", "Unable to schedule icon retry", it) }
        }
    }
}
