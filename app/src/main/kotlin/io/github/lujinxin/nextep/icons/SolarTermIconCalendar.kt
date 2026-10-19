package io.github.lujinxin.nextep.icons

import com.nlf.calendar.Lunar
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Solar-term instants are calculated offline in the Chinese calendar's UTC+8. */
internal object SolarTermIconCalendar {
    data class Schedule(val style: LauncherIconStyle, val nextChange: Instant)
    private data class Transition(val style: LauncherIconStyle, val instant: Instant)

    private val calendarOffset = ZoneOffset.ofHours(8)
    private val cache = linkedMapOf<Int, List<Transition>>()
    private val seasonTerms = listOf(
        "立春" to LauncherIconStyle.SPRING,
        "立夏" to LauncherIconStyle.SUMMER,
        "立秋" to LauncherIconStyle.AUTUMN,
        "立冬" to LauncherIconStyle.WINTER,
    )

    @Synchronized
    fun resolve(now: Instant): Schedule {
        val year = now.atOffset(calendarOffset).year
        val boundaries = transitionsForYear(year)
        // January and the days before Lichun still belong to the previous winter.
        val current = boundaries.lastOrNull { !now.isBefore(it.instant) }
            ?.style ?: LauncherIconStyle.WINTER
        val next = boundaries.firstOrNull { now.isBefore(it.instant) }
            ?: transitionsForYear(year + 1).first()
        return Schedule(current, next.instant)
    }

    private fun transitionsForYear(year: Int): List<Transition> = cache.getOrPut(year) {
        if (cache.size >= 2) cache.remove(cache.keys.first())
        val terms = Lunar.fromYmd(year, 1, 1).jieQiTable
        seasonTerms.map { (name, style) ->
            val solar = checkNotNull(terms[name]) { "Missing solar term: $name" }
            check(solar.year == year) { "Unexpected solar-term year for $name" }
            Transition(style, LocalDateTime.of(
                solar.year, solar.month, solar.day, solar.hour, solar.minute, solar.second,
            ).toInstant(calendarOffset))
        }.sortedBy { it.instant }
    }
}
