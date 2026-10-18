package app.railwaytime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextClock
import android.widget.TextView
import app.railwaytime.core.DayTime
import app.railwaytime.core.RailClock
import java.time.Instant

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btn_duty).setOnClickListener { Duty.toggle(this); render() }
        findViewById<Button>(R.id.btn_signin).setOnClickListener { startActivity(Intent(this, IvrmActivity::class.java)) }
        findViewById<Button>(R.id.btn_settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.btn_share).setOnClickListener { sharePageText() }
        RefreshScheduler.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun render() {
        val store = Store(this)
        val settings = store.settings
        val instant = Instant.now()
        findViewById<TextClock>(R.id.rail_clock).timeZone = RailClock.railZoneId(settings, instant)
        findViewById<TextClock>(R.id.local_clock).timeZone = settings.homeZone.id
        val offset = RailClock.offsetMinutes(settings, instant)
        findViewById<TextView>(R.id.rail_offset).text =
            if (offset == 0) "Railtime = local time" else "Railtime = local ${if (offset > 0) "+" else ""}${offset} min"

        val onDuty = store.onDuty
        findViewById<Button>(R.id.btn_duty).text = if (onDuty) "ON DUTY — tap when off duty" else "OFF DUTY — tap when on duty"
        findViewById<Button>(R.id.btn_duty).setBackgroundResource(if (onDuty) R.drawable.duty_on else R.drawable.duty_off)
        findViewById<View>(R.id.board_section).visibility = if (onDuty) View.GONE else View.VISIBLE
        findViewById<View>(R.id.on_duty_note).visibility = if (onDuty) View.VISIBLE else View.GONE
        if (onDuty) return

        val s = Summary.build(this)
        findViewById<TextView>(R.id.status).text = s.updated
        findViewById<TextView>(R.id.summary).text = listOf(s.board, s.rest, s.nextBack).filter { it.isNotEmpty() }.joinToString("\n")
        findViewById<TextView>(R.id.train).text = s.train

        val p = s.prediction
        val snapshot = store.snapshot()
        findViewById<TextView>(R.id.order).text = p?.assignments?.joinToString("\n") { a ->
            "%-11s %s  ord %s  → %s".format(
                a.train.label, DayTime.format(a.train.planned), DayTime.clock(a.orderAt),
                if (a.isMe) "YOU" else a.who ?: "past you (resting)",
            )
        }?.ifEmpty { "No upcoming trains in the pool lineup" } ?: "—"
        findViewById<TextView>(R.id.board).text = snapshot?.board?.joinToString("\n") { e ->
            val status = (if (e.spare) "** " else "") + e.offStatus
            val back = e.returnAt?.let { "back ${DayTime.format(it)}" } ?: ""
            "%02d  %-5s %-6s %s".format(e.pos, e.init, status, back).trimEnd()
        }?.ifEmpty { "No board rows found" } ?: "—"
        findViewById<TextView>(R.id.cats).text = snapshot?.cats?.let { c ->
            listOfNotNull(
                c.name, c.terminal?.let { "Terminal $it" }, c.status, c.assignment,
                c.location?.let { "Location $it" },
            ).joinToString("\n")
        } ?: "Sign in to IVRM to load your board."
    }

    /** Sends the raw page text (e.g. to yourself) if the board isn't read correctly. */
    private fun sharePageText() {
        val text = Store(this).pageText ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share IVRM page text"))
    }
}
