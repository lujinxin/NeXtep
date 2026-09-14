package io.github.lujinxin.nextep.framework

import android.hardware.display.DisplayManager
import io.github.lujinxin.nextep.logging.NeXtepLog

object VirtualDisplayFlagsCompat {
    data class Resolved(val value: Int, val names: List<String>)

    fun resolveCandidates(): List<Resolved> {
        val fallbackValue = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        var value = fallbackValue
        val names = mutableListOf("OWN_CONTENT_ONLY", "PRESENTATION")

        listOf(
            "VIRTUAL_DISPLAY_FLAG_TRUSTED" to "TRUSTED",
            "VIRTUAL_DISPLAY_FLAG_OWN_FOCUS" to "OWN_FOCUS",
            "VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP" to "OWN_DISPLAY_GROUP",
        ).forEach { (fieldName, label) ->
            readFlag(fieldName)?.let { flag ->
                value = value or flag
                names += label
            }
        }

        val preferred = Resolved(value, names)
        val fallback = Resolved(
            fallbackValue,
            listOf("OWN_CONTENT_ONLY", "PRESENTATION"),
        )
        return listOf(preferred, fallback).distinctBy { it.value }.also { candidates ->
            NeXtepLog.info(
                "virtual_display_flags",
                "Resolved candidates=${candidates.joinToString { "${it.value}:${it.names}" }}",
            )
        }
    }

    private fun readFlag(name: String): Int? = runCatching {
        DisplayManager::class.java.getDeclaredField(name).apply { isAccessible = true }.getInt(null)
    }.onFailure { error ->
        NeXtepLog.debug("virtual_display_flags", "Hidden flag unavailable name=$name error=${error.javaClass.simpleName}")
    }.getOrNull()
}
