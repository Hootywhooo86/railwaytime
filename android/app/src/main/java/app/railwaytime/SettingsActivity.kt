package app.railwaytime

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Toast
import app.railwaytime.core.AppSettings
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SettingsActivity : Activity() {
    private val switchFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val store = Store(this)
        val s = store.settings

        val zone = findViewById<EditText>(R.id.set_zone)
        val before = findViewById<EditText>(R.id.set_offset_before)
        val switchAt = findViewById<EditText>(R.id.set_switch_at)
        val after = findViewById<EditText>(R.id.set_offset_after)
        val boardRail = findViewById<RadioButton>(R.id.set_board_rail)
        val boardLocal = findViewById<RadioButton>(R.id.set_board_local)
        val lead = findViewById<EditText>(R.id.set_call_lead)
        val refresh = findViewById<EditText>(R.id.set_refresh)
        val ivrm = findViewById<EditText>(R.id.set_ivrm)
        val sharepoint = findViewById<EditText>(R.id.set_sharepoint)

        zone.setText(s.homeZoneId)
        zone.hint = "Phone: ${ZoneId.systemDefault().id} (e.g. America/Edmonton)"
        before.setText(s.offsetBeforeMin.toString())
        switchAt.setText(s.switchAt?.format(switchFormat) ?: "")
        after.setText(s.offsetAfterMin.toString())
        boardRail.isChecked = s.boardInRailtime
        boardLocal.isChecked = !s.boardInRailtime
        lead.setText(s.callLeadMin.toString())
        refresh.setText(s.refreshMin.toString())
        ivrm.setText(s.ivrmUrl)
        sharepoint.setText(s.sharepointUrl)

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            val zoneText = zone.text.toString().trim()
            if (zoneText.isNotEmpty() && runCatching { ZoneId.of(zoneText) }.isFailure) {
                zone.error = "Unknown time zone"
                return@setOnClickListener
            }
            val switchText = switchAt.text.toString().trim()
            val switch = if (switchText.isEmpty()) null else runCatching { LocalDateTime.parse(switchText, switchFormat) }.getOrNull()
            if (switchText.isNotEmpty() && switch == null) {
                switchAt.error = "Use YYYY-MM-DD HH:MM, or leave blank"
                return@setOnClickListener
            }
            val d = AppSettings()
            store.settings = AppSettings(
                homeZoneId = zoneText,
                offsetBeforeMin = before.text.toString().trim().toIntOrNull() ?: 0,
                switchAt = switch,
                offsetAfterMin = after.text.toString().trim().toIntOrNull() ?: 0,
                boardInRailtime = boardRail.isChecked,
                callLeadMin = lead.text.toString().trim().toIntOrNull() ?: d.callLeadMin,
                refreshMin = (refresh.text.toString().trim().toIntOrNull() ?: d.refreshMin).coerceAtLeast(5),
                ivrmUrl = ivrm.text.toString().trim().ifEmpty { d.ivrmUrl },
                sharepointUrl = sharepoint.text.toString().trim().ifEmpty { d.sharepointUrl },
            )
            RefreshScheduler.schedule(this)
            Widget.updateAll(this)
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
