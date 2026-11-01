package app.railwaytime

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Toast
import app.railwaytime.core.AppSettings
import app.railwaytime.core.Themes
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

        findViewById<Button>(R.id.theme_reset).setOnClickListener {
            store.themeAccent = 0
            store.themeAccent2 = 0
            themeChanged()
        }
        buildColours()

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
            RefreshScheduler.scheduleNext(this)
            Widget.updateAll(this)
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        Themer.apply(this)
    }

    /** Colours apply straight away: here, in the app and on the widget. */
    private fun themeChanged() {
        buildColours()
        Themer.apply(this)
        Widget.updateAll(this)
    }

    private fun buildColours() {
        val store = Store(this)
        val current = Themer.current(this)
        val dp = resources.displayMetrics.density

        val presets = findViewById<LinearLayout>(R.id.theme_presets)
        presets.removeAllViews()
        Themes.PRESETS.forEach { t ->
            val selected = t.id == (store.themeId ?: Themes.DEFAULT.id)
            presets.addView(TextView(this).apply {
                text = t.name
                setTextColor(t.accent)
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(t.bg)
                    cornerRadius = 10 * dp
                    setStroke(((if (selected) 3 else 1) * dp).toInt(), if (selected) t.accent2 else t.accent)
                }
                setTag(R.id.theme_orig_text, 0) // keep its own colours when the screen is themed
                setOnClickListener {
                    store.themeId = t.id
                    store.themeAccent = 0
                    store.themeAccent2 = 0
                    themeChanged()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = (8 * dp).toInt() }
            })
        }

        fun swatches(row: Int, chosen: Int, pick: (Int) -> Unit) {
            val box = findViewById<LinearLayout>(row)
            box.removeAllViews()
            Themes.SWATCHES.forEach { c ->
                box.addView(View(this).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(c)
                        cornerRadius = 6 * dp
                        if (c == chosen) setStroke((3 * dp).toInt(), current.ink)
                    }
                    setTag(R.id.theme_orig_bg, 0)
                    contentDescription = "Colour #%06X".format(c and 0xFFFFFF)
                    setOnClickListener { pick(c); themeChanged() }
                    layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt())
                        .apply { marginEnd = (8 * dp).toInt() }
                })
            }
        }
        swatches(R.id.theme_accent, current.accent) { store.themeAccent = it }
        swatches(R.id.theme_accent2, current.accent2) { store.themeAccent2 = it }
    }
}
