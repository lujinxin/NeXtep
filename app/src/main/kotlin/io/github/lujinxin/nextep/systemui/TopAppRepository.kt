package io.github.lujinxin.nextep.systemui

import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

class TopAppRepository(context: Context) {
    data class AppEntry(
        val label: CharSequence,
        val icon: Drawable,
        val launchIntent: Intent,
        val lastTimeUsed: Long,
    )

    private val applicationContext = context.applicationContext ?: context
    private val packageManager = applicationContext.packageManager

    fun load(preferredComponents: List<String> = emptyList()): List<AppEntry> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val usage = recentUsage()
        return packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        )
            .asSequence()
            .filter { it.activityInfo?.exported == true && it.activityInfo?.enabled == true }
            .filterNot { it.activityInfo?.packageName == "com.android.stk" }
            .distinctBy { info ->
                val activity = checkNotNull(info.activityInfo)
                ComponentName(activity.packageName, activity.name)
            }
            .map { info ->
                val activity = checkNotNull(info.activityInfo)
                val component = ComponentName(activity.packageName, activity.name)
                AppEntry(
                    label = info.loadLabel(packageManager),
                    icon = info.loadIcon(packageManager),
                    launchIntent = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    lastTimeUsed = usage[activity.packageName] ?: 0L,
                )
            }
            .toList()
            .let { entries ->
                if (preferredComponents.isEmpty()) {
                    entries.sortedWith(
                        compareByDescending<AppEntry> { it.lastTimeUsed }
                            .thenBy { it.label.toString().lowercase() },
                    )
                } else {
                    val order = preferredComponents.withIndex()
                        .associate { (index, value) -> value to index }
                    entries.filter { entry ->
                        entry.launchIntent.component?.flattenToString() in order
                    }.sortedBy { entry ->
                        order[entry.launchIntent.component?.flattenToString()] ?: Int.MAX_VALUE
                    }
                }
            }
            .take(MAX_VISIBLE_APPS)
    }

    private fun recentUsage(): Map<String, Long> = runCatching {
        val manager = applicationContext.getSystemService(UsageStatsManager::class.java)
            ?: return@runCatching emptyMap()
        val end = System.currentTimeMillis()
        manager.queryAndAggregateUsageStats(end - USAGE_WINDOW_MS, end)
            .mapValues { (_, stats) -> stats.lastTimeUsed }
    }.getOrDefault(emptyMap())

    private companion object {
        const val MAX_VISIBLE_APPS = 36
        const val USAGE_WINDOW_MS = 7L * 24L * 60L * 60L * 1_000L
    }
}
