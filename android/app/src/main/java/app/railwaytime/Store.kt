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

    fun saveBoard(text: String) {
        val now = System.currentTimeMillis()
        prefs.edit()
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
