package io.github.lujinxin.nextep.icons

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SolarTermIconCalendarTest {
    private val chinaOffset = ZoneOffset.ofHours(8)

    @Test
    fun boundaryDatesMatchThePublished2026Almanac() {
        // Independent date reference: Hong Kong Observatory's 2026 calendar.
        // https://www.hko.gov.hk/en/gts/time/calendar/pdf/files/2026e.pdf
        val cases = listOf(
            Triple("2026-01-15T12:00:00+08:00", LauncherIconStyle.WINTER, "2026-02-04"),
            Triple("2026-03-15T12:00:00+08:00", LauncherIconStyle.SPRING, "2026-05-05"),
            Triple("2026-06-15T12:00:00+08:00", LauncherIconStyle.SUMMER, "2026-08-07"),
            Triple("2026-09-15T12:00:00+08:00", LauncherIconStyle.AUTUMN, "2026-11-07"),
        )
        for ((now, expectedStyle, expectedDate) in cases) {
            val schedule = SolarTermIconCalendar.resolve(Instant.parse(now))
            assertEquals(expectedStyle, schedule.style)
            assertEquals(LocalDate.parse(expectedDate), schedule.nextChange.atOffset(chinaOffset).toLocalDate())
        }
    }

    @Test
    fun eachNewSeasonBeginsAtTheTransitionInstant() {
        val cases = listOf(
            Triple("2026-01-15T00:00:00Z", LauncherIconStyle.WINTER, LauncherIconStyle.SPRING),
            Triple("2026-03-15T00:00:00Z", LauncherIconStyle.SPRING, LauncherIconStyle.SUMMER),
            Triple("2026-06-15T00:00:00Z", LauncherIconStyle.SUMMER, LauncherIconStyle.AUTUMN),
            Triple("2026-09-15T00:00:00Z", LauncherIconStyle.AUTUMN, LauncherIconStyle.WINTER),
        )
        for ((now, previous, next) in cases) {
            val boundary = SolarTermIconCalendar.resolve(Instant.parse(now)).nextChange
            assertEquals(previous, SolarTermIconCalendar.resolve(boundary.minusNanos(1)).style)
            val atBoundary = SolarTermIconCalendar.resolve(boundary)
            assertEquals(next, atBoundary.style)
            assertTrue(atBoundary.nextChange.isAfter(boundary))
        }
    }

    @Test
    fun winterContinuesAcrossGregorianNewYear() {
        val endOfYear = SolarTermIconCalendar.resolve(Instant.parse("2026-12-31T23:59:59+08:00"))
        val newYear = SolarTermIconCalendar.resolve(Instant.parse("2027-01-01T00:00:00+08:00"))
        assertEquals(LauncherIconStyle.WINTER, endOfYear.style)
        assertEquals(LauncherIconStyle.WINTER, newYear.style)
        assertEquals(endOfYear.nextChange, newYear.nextChange)
        assertEquals(LocalDate.of(2027, 2, 4), newYear.nextChange.atOffset(chinaOffset).toLocalDate())
    }

    @Test
    fun springContinuesThroughLeapDayAndMarchFirst() {
        val leapDay = SolarTermIconCalendar.resolve(Instant.parse("2028-02-29T23:59:59+08:00"))
        val march = SolarTermIconCalendar.resolve(Instant.parse("2028-03-01T00:00:00+08:00"))
        assertEquals(LauncherIconStyle.SPRING, leapDay.style)
        assertEquals(LauncherIconStyle.SPRING, march.style)
        assertEquals(leapDay.nextChange, march.nextChange)
    }
}
