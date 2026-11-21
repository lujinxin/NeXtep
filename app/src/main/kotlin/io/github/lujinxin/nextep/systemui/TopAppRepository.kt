package io.github.lujinxin.nextep.systemui

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import io.github.lujinxin.nextep.apps.LauncherAppRepository

class TopAppRepository(context: Context) {
    data class AppEntry(
        val key: String,
        val label: CharSequence,
        val icon: Drawable,
        val launchIntent: Intent,
        val lastTimeUsed: Long,
    )

    data class Snapshot(
        val visibleApps: List<AppEntry>,
        val obstacleIcons: Map<String, Bitmap>,
    )

    private val applicationContext = context.applicationContext ?: context
    private val apps = LauncherAppRepository(applicationContext)

    fun load(preferredComponents: List<String> = emptyList(),
        manualOrder: Boolean = preferredComponents.isNotEmpty()): Snapshot {
        val catalogue = apps.load()
        val usage = catalogue.map { it.userId }.distinct().associateWith { userId ->
            apps.contextForUser(userId)?.let(::recentUsage).orEmpty()
        }
        val visibleApps = catalogue.asSequence()
            .map { app ->
                AppEntry(
                    key = app.key,
                    label = app.label,
                    icon = app.icon,
                    launchIntent = app.launchIntent(),
                    lastTimeUsed = usage[app.userId]?.get(app.component.packageName) ?: 0L,
                )
            }
            .toList()
            .let { entries ->
                if (!manualOrder) {
                    entries.sortedWith(
                        compareByDescending<AppEntry> { it.lastTimeUsed }
                            .thenBy { it.label.toString().lowercase() },
                    )
                } else {
                    val order = preferredComponents.withIndex()
                        .associate { (index, value) -> value to index }
                    entries.filter { entry ->
                        entry.key in order
                    }.sortedBy { entry ->
                        order[entry.key] ?: Int.MAX_VALUE
                    }
                }
            }
            .take(MAX_VISIBLE_APPS)
        return Snapshot(visibleApps, obstacleIcons(catalogue))
    }

    private fun obstacleIcons(catalogue: List<LauncherAppRepository.AppEntry>): Map<String, Bitmap> {
        val pixels = IntArray(OBSTACLE_ICON_SIZE * OBSTACLE_ICON_SIZE)
        val iconsByPixelHash = mutableMapOf<Int, MutableList<Bitmap>>()
        return buildMap {
            for (app in catalogue) {
                val icon = runCatching {
                    app.icon.toBitmap(OBSTACLE_ICON_SIZE, OBSTACLE_ICON_SIZE, Bitmap.Config.ARGB_8888)
                        .also {
                            it.getPixels(pixels, 0, OBSTACLE_ICON_SIZE, 0, 0,
                                OBSTACLE_ICON_SIZE, OBSTACLE_ICON_SIZE)
                        }
                }.getOrNull() ?: continue
                val matchingIcons = iconsByPixelHash.getOrPut(pixels.contentHashCode()) { mutableListOf() }
                if (matchingIcons.any { it.sameAs(icon) }) continue
                matchingIcons.add(icon)
                put(app.key, icon)
            }
        }
    }

    private fun recentUsage(context: Context): Map<String, Long> = runCatching {
        val manager = context.getSystemService(UsageStatsManager::class.java)
            ?: return@runCatching emptyMap()
        val end = System.currentTimeMillis()
        manager.queryAndAggregateUsageStats(end - USAGE_WINDOW_MS, end)
            .mapValues { (_, stats) -> stats.lastTimeUsed }
    }.getOrDefault(emptyMap())

    private companion object {
        const val MAX_VISIBLE_APPS = 36
        const val OBSTACLE_ICON_SIZE = 64
        const val USAGE_WINDOW_MS = 7L * 24L * 60L * 60L * 1_000L
    }
}
