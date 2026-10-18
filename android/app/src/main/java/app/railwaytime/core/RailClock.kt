package app.railwaytime.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

data class AppSettings(
    /** Your local time zone; blank = the phone's. */
    val homeZoneId: String = "",
    /** Railtime minus local time, before [switchAt]. */
    val offsetBeforeMin: Int = 0,
    /** Local time when railtime changes; null = never. Defaults to the Nov 1 fall-back. */
    val switchAt: LocalDateTime? = LocalDateTime.of(2026, 11, 1, 2, 0),
    /** Railtime minus local time, from [switchAt] on. */
    val offsetAfterMin: Int = -60,
    /** Are the times on IVRM in railtime (true) or local time (false)? */
    val boardInRailtime: Boolean = true,
    /** You get ordered this long before a train's planned time. */
    val callLeadMin: Int = 120,
    val refreshMin: Int = 10,
    val ivrmUrl: String = "https://ivrm.cn.ca/",
    val sharepointUrl: String = "https://cnrail.sharepoint.com/sites/CN/SitePages/Home.aspx",
) {
    val homeZone: ZoneId
        get() = homeZoneId.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneId.systemDefault()
}

/** Railtime is local time plus an offset that can change on a set date. */
object RailClock {
    fun offsetMinutes(s: AppSettings, now: Instant): Int {
        val local = LocalDateTime.ofInstant(now, s.homeZone)
        return if (s.switchAt != null && !local.isBefore(s.switchAt)) s.offsetAfterMin else s.offsetBeforeMin
    }

    fun localNow(s: AppSettings, now: Instant): LocalDateTime = LocalDateTime.ofInstant(now, s.homeZone)

    fun railNow(s: AppSettings, now: Instant): LocalDateTime = localNow(s, now).plusMinutes(offsetMinutes(s, now).toLong())

    /** "Now" in the clock IVRM's times are written in. */
    fun boardNow(s: AppSettings, now: Instant): LocalDateTime = if (s.boardInRailtime) railNow(s, now) else localNow(s, now)

    /** A fixed-offset zone id for railtime right now, e.g. "GMT-07:00" (usable by TextClock). */
    fun railZoneId(s: AppSettings, now: Instant): String {
        val total = s.homeZone.rules.getOffset(now).totalSeconds / 60 + offsetMinutes(s, now)
        val sign = if (total < 0) "-" else "+"
        val abs = kotlin.math.abs(total)
        return "GMT%s%02d:%02d".format(sign, abs / 60, abs % 60)
    }

    /** The instant railtime next changes offset, if it is still ahead. */
    fun nextSwitch(s: AppSettings, now: Instant): Instant? =
        s.switchAt?.atZone(s.homeZone)?.toInstant()?.takeIf { it.isAfter(now) }
}
