package app.railwaytime

import android.content.Context
import app.railwaytime.core.AppSettings
import app.railwaytime.core.BoardParser
import app.railwaytime.core.BoardSnapshot
import java.time.LocalDate
import java.time.LocalDateTime

/** Everything stays on the phone, in the app's private preferences. */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("railwaytime", Context.MODE_PRIVATE)

    enum class Status { NONE, OK, SIGN_IN, ERROR }

    var onDuty: Boolean
        get() = prefs.getBoolean("on_duty", false)
        set(v) = prefs.edit().putBoolean("on_duty", v).apply()

    /** Last good page text from IVRM; parsed again whenever it's shown. */
    val pageText: String? get() = prefs.getString("page_text", null)
    val fetchedAt: Long get() = prefs.getLong("fetched_at", 0)
    val status: Status get() = runCatching { Status.valueOf(prefs.getString("status", null)!!) }.getOrDefault(Status.NONE)
    val statusDetail: String? get() = prefs.getString("status_detail", null)
    val statusAt: Long get() = prefs.getLong("status_at", 0)

    /** The page the board was last read from (often reached via SharePoint → Mobile Crew). */
    val boardUrl: String? get() = prefs.getString("board_url", null)

    fun saveBoard(text: String, url: String?) {
        val now = System.currentTimeMillis()
        val edit = prefs.edit()
        if (url != null && url.startsWith("http")) edit.putString("board_url", url)
        edit
            .putString("page_text", text)
            .putLong("fetched_at", now)
            .putString("status", Status.OK.name)
            .remove("status_detail")
            .putLong("status_at", now)
            .apply()
    }

    fun setStatus(status: Status, detail: String? = null) {
        prefs.edit()
            .putString("status", status.name)
            .putString("status_detail", detail)
            .putLong("status_at", System.currentTimeMillis())
            .apply()
    }

    /** Recent sign-in/refresh steps (addresses without query strings, errors) for "Share debug info". */
    val log: List<String> get() = prefs.getString("log", "")!!.lines().filter { it.isNotBlank() }

    fun log(line: String) {
        val stamp = java.time.LocalTime.now().withNano(0)
        prefs.edit().putString("log", (log + "$stamp $line").takeLast(80).joinToString("\n")).apply()
    }

    /** A refresh is running (shown as "Refreshing…"); ignored if older than two minutes. */
    var refreshing: Boolean
        get() = System.currentTimeMillis() - prefs.getLong("refreshing_since", 0) < 2 * 60_000L
        set(v) = prefs.edit().putLong("refreshing_since", if (v) System.currentTimeMillis() else 0).apply()

    /** When the next automatic refresh is due (0 = none armed). */
    var nextRefreshAt: Long
        get() = prefs.getLong("next_refresh_at", 0)
        set(v) = prefs.edit().putLong("next_refresh_at", v).apply()

    /** When a refresh last started, successful or not. */
    var lastAttemptAt: Long
        get() = prefs.getLong("last_attempt_at", 0)
        set(v) = prefs.edit().putLong("last_attempt_at", v).apply()

    /** Nap Stop alarm time (0 = none). */
    var napAt: Long
        get() = prefs.getLong("nap_at", 0)
        set(v) = prefs.edit().putLong("nap_at", v).apply()

    enum class NapSound { HORN, PHONE }

    var napSound: NapSound
        get() = runCatching { NapSound.valueOf(prefs.getString("nap_sound", null)!!) }.getOrDefault(NapSound.HORN)
        set(v) = prefs.edit().putString("nap_sound", v.name).apply()

    /** The device certificate you picked for CN, reused by background refreshes. */
    var certAlias: String?
        get() = prefs.getString("cert_alias", null)
        set(v) = prefs.edit().putString("cert_alias", v).apply()

    fun snapshot(): BoardSnapshot? = pageText?.let { BoardParser.parse(it, LocalDate.now()) }

    var settings: AppSettings
        get() {
            val d = AppSettings()
            return AppSettings(
                homeZoneId = prefs.getString("home_zone", d.homeZoneId)!!,
                offsetBeforeMin = prefs.getInt("offset_before", d.offsetBeforeMin),
                switchAt = if (prefs.contains("switch_at")) {
                    prefs.getString("switch_at", null)?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                } else d.switchAt,
                offsetAfterMin = prefs.getInt("offset_after", d.offsetAfterMin),
                boardInRailtime = prefs.getBoolean("board_in_railtime", d.boardInRailtime),
                callLeadMin = prefs.getInt("call_lead", d.callLeadMin),
                refreshMin = prefs.getInt("refresh_min", d.refreshMin),
                ivrmUrl = prefs.getString("ivrm_url", d.ivrmUrl)!!,
                sharepointUrl = prefs.getString("sharepoint_url", d.sharepointUrl)!!,
            )
        }
        set(s) {
            prefs.edit()
                .putString("home_zone", s.homeZoneId)
                .putInt("offset_before", s.offsetBeforeMin)
                .putString("switch_at", s.switchAt?.toString() ?: "")
                .putInt("offset_after", s.offsetAfterMin)
                .putBoolean("board_in_railtime", s.boardInRailtime)
                .putInt("call_lead", s.callLeadMin)
                .putInt("refresh_min", s.refreshMin)
                .putString("ivrm_url", s.ivrmUrl)
                .putString("sharepoint_url", s.sharepointUrl)
                .apply()
        }
}
