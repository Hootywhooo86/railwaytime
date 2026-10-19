package app.railwaytime.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime

/** Nap Stop alarm times. You pick times on the railtime clock; alarms run on real instants. */
object NapTime {
    /** The next time the railtime clock reads [time] (later today, else tomorrow), as an instant. */
    fun nextRailtime(s: AppSettings, now: Instant, time: LocalTime): Instant {
        val railNow = RailClock.railNow(s, now)
        var at = railNow.toLocalDate().atTime(time)
        if (!at.isAfter(railNow)) at = at.plusDays(1)
        return railToInstant(s, now, at)
    }

    /** Railtime wall-clock → instant, using the railtime offset in force at [now]. */
    fun railToInstant(s: AppSettings, now: Instant, rail: LocalDateTime): Instant =
        rail.minusMinutes(RailClock.offsetMinutes(s, now).toLong()).atZone(s.homeZone).toInstant()

    /** An instant as railtime wall-clock. */
    fun railtimeOf(s: AppSettings, at: Instant): LocalDateTime = RailClock.railNow(s, at)

    /** Things to say when the nap is over. */
    val WAKE_LINES = listOf(
        "Your nap has reached the end of the line.",
        "This is your wake-up call. Literally.",
        "Signal's green. Time to roll.",
        "Next stop: on duty.",
        "Crew change! That's you.",
        "Rise and grind those rails.",
    )
}
