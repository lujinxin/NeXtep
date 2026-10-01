package io.github.lujinxin.nextep.config

object TopContentMode {
    const val ICON = "icon"
    const val TEXT = "text"
    const val TIME = "time"
    const val DATE = "date"
    const val EMPTY = "empty"
    val values = listOf(ICON, TEXT, TIME, DATE, EMPTY)

    fun resolve(value: String?, legacyTitle: String): String =
        value?.takeIf { it in values } ?: if (legacyTitle.isBlank()) ICON else TEXT
}
