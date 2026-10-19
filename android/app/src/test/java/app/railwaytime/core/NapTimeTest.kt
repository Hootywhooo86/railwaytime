package app.railwaytime.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime

class NapTimeTest {
    private val s = AppSettings(homeZoneId = "America/Edmonton")

    @Test fun laterTodayOrTomorrow() {
        val now = Instant.parse("2026-10-19T04:00:00Z") // 22:00 MDT Oct 18
        assertEquals(LocalDateTime.of(2026, 10, 18, 23, 30), NapTime.railtimeOf(s, NapTime.nextRailtime(s, now, LocalTime.of(23, 30))))
        assertEquals(LocalDateTime.of(2026, 10, 19, 6, 0), NapTime.railtimeOf(s, NapTime.nextRailtime(s, now, LocalTime.of(6, 0))))
        assertEquals(LocalDateTime.of(2026, 10, 19, 22, 0), NapTime.railtimeOf(s, NapTime.nextRailtime(s, now, LocalTime.of(22, 0))))
    }

    @Test fun railtimeAfterFallBack() {
        // Nov 2, early morning: railtime is local minus 60 min. Railtime 07:00 rings at local 08:00.
        val now = Instant.parse("2026-11-02T12:00:00Z")
        val at = NapTime.nextRailtime(s, now, LocalTime.of(7, 0))
        assertEquals(LocalDateTime.of(2026, 11, 2, 7, 0), NapTime.railtimeOf(s, at))
        assertEquals(LocalDateTime.of(2026, 11, 2, 8, 0), RailClock.localNow(s, at))
    }
}
