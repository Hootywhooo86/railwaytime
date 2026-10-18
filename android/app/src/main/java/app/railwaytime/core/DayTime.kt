package app.railwaytime.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * IVRM writes times as "<day of month> <time>": "18 22:50", "19 0559".
 * The month and year come from the nearest date to [ref].
 */
object DayTime {
    private val PATTERN = Regex("""^(\d{1,2})\s+(\d{1,2}):?(\d{2})$""")
    private val FMT = DateTimeFormatter.ofPattern("dd HH:mm")
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    fun matches(s: String): Boolean = PATTERN.matches(s.trim())

    fun parse(s: String?, ref: LocalDate): LocalDateTime? {
        val m = PATTERN.matchEntire(s?.trim() ?: return null) ?: return null
        val (d, h, min) = m.destructured
        return resolve(d.toInt(), h.toInt(), min.toInt(), ref)
    }

    fun resolve(day: Int, hour: Int, minute: Int, ref: LocalDate): LocalDateTime? {
        if (hour > 23 || minute > 59) return null
        val base = YearMonth.from(ref)
        return listOf(base.minusMonths(1), base, base.plusMonths(1))
            .filter { day in 1..it.lengthOfMonth() }
            .map { it.atDay(day) }
            .minByOrNull { abs(it.toEpochDay() - ref.toEpochDay()) }
            ?.atTime(hour, minute)
    }

    fun format(t: LocalDateTime): String = t.format(FMT)

    fun clock(t: LocalDateTime): String = t.format(CLOCK)

    /** "in 7h06", "in 12m", "now". */
    fun until(now: LocalDateTime, t: LocalDateTime): String {
        val mins = java.time.Duration.between(now, t).toMinutes()
        if (mins <= 0) return "now"
        val h = mins / 60
        val m = mins % 60
        return if (h == 0L) "in ${m}m" else "in ${h}h%02d".format(m)
    }
}
