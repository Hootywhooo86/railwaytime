package app.railwaytime

import android.app.Activity
import android.content.ClipboardManager
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
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
            handler.postDelayed(this, if (Store(this@MainActivity).refreshing) 2_000 else 15_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btn_duty).setOnClickListener { Duty.toggle(this); render() }
        findViewById<Button>(R.id.btn_signin).setOnClickListener { startActivity(Intent(this, IvrmActivity::class.java)) }
        findViewById<Button>(R.id.btn_settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.btn_share).setOnClickListener { shareDebug() }
        findViewById<Button>(R.id.btn_chrome_update).setOnClickListener { updateFromChrome() }
        findViewById<Button>(R.id.btn_nap).setOnClickListener { startActivity(Intent(this, NapActivity::class.java)) }
        findViewById<Button>(R.id.btn_refresh).setOnClickListener {
            Store(this).refreshing = true
            RefreshScheduler.refreshNow(this, manual = true)
            render()
        }
        findViewById<Button>(R.id.btn_battery).setOnClickListener {
            @SuppressLint("BatteryLife")
            val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            runCatching { startActivity(ask) }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        findViewById<Button>(R.id.btn_screenshot).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICK_IMAGE)
        }
        findViewById<Button>(R.id.btn_paste).setOnClickListener {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            Importer.toast(this, text != null && Importer.importText(this, text, "paste"))
            render()
        }
        RefreshScheduler.ensure(this)
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

        // Battery optimisation can hold the 10-minute refresh back for hours.
        findViewById<Button>(R.id.btn_battery).visibility =
            if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE

        findViewById<Button>(R.id.btn_nap).text = store.napAt.takeIf { it > System.currentTimeMillis() }?.let {
            "NAP STOP  ⏰ " + DayTime.clock(app.railwaytime.core.NapTime.railtimeOf(settings, Instant.ofEpochMilli(it)))
        } ?: "NAP STOP  💤🚂"

        val onDuty = store.onDuty
        findViewById<Button>(R.id.btn_duty).text = if (onDuty) "ON DUTY — tap when off duty" else "OFF DUTY — tap when on duty"
        findViewById<Button>(R.id.btn_duty).setBackgroundResource(if (onDuty) R.drawable.duty_on else R.drawable.duty_off)
        findViewById<View>(R.id.board_section).visibility = if (onDuty) View.GONE else View.VISIBLE
        findViewById<View>(R.id.on_duty_note).visibility = if (onDuty) View.VISIBLE else View.GONE
        if (onDuty) return

        val s = Summary.build(this)
        findViewById<TextView>(R.id.status).text =
            if (store.status == Store.Status.ERROR || store.status == Store.Status.BLOCKED) {
                "${s.updated}\nWhy: ${store.statusDetail.orEmpty()}"
            } else s.updated
        findViewById<TextView>(R.id.summary).text = if (!s.hasBoard) "No board yet" else listOf(
            "Position ${s.position} · ${s.ahead} ahead (${s.aheadSub})" + (s.location?.let { " · $it" } ?: ""),
            "${s.beforeYou} likely called before you",
            "Your rest: ${s.rest} (${s.restSub})",
            "Next off rest: ${s.next} (${s.nextSub})",
        ).joinToString("\n")
        findViewById<TextView>(R.id.train).text = listOfNotNull(
            s.trainBefore?.let { "   $it" },
            s.train,
            s.trainSub,
            *s.trainsAfter.map { "   $it" }.toTypedArray(),
        ).joinToString("\n")

        val p = s.prediction
        val snapshot = store.snapshot()
        findViewById<TextView>(R.id.order).text = p?.assignments?.joinToString("\n\n") { a ->
            "%-11s → %s\n  %s %s · Call %s".format(
                a.train.label,
                if (a.isMe) "YOU" else a.who ?: "behind you",
                a.train.statusWord,
                DayTime.format(a.train.planned),
                DayTime.clock(a.orderAt),
            )
        }?.ifEmpty { "No upcoming trains in the pool lineup" } ?: "—"
        findViewById<TextView>(R.id.board).text = snapshot?.board?.joinToString("\n") { e ->
            val status = if (e.spare) "spare" else e.offStatus
            val back = listOfNotNull(
                e.restUntil?.let { "rest ${DayTime.format(it)}" },
                e.returnAt?.let { "back ${DayTime.format(it)}" },
            ).joinToString(" · ")
            "%02d  %-5s %-6s %s".format(e.pos, e.init, status, back).trimEnd()
        }?.ifEmpty { "No board rows found" } ?: "—"
        findViewById<TextView>(R.id.cats).text = snapshot?.cats?.let { c ->
            listOfNotNull(
                c.name, c.terminal?.let { "Terminal $it" }, c.status, c.assignment,
                c.location?.let { "Location $it" },
            ).joinToString("\n")
        } ?: "Sign in to IVRM to load your board."
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != PICK_IMAGE || resultCode != RESULT_OK || uri == null) return
        Importer.importImage(this, uri) { ok ->
            Importer.toast(this, ok)
            render()
        }
    }

    /**
     * When CN won't let the app's browser in: open IVRM in Chrome (an approved browser) and send
     * the board back here — the text by Share, or a screenshot.
     */
    private fun updateFromChrome() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Update from Chrome")
            .setMessage(
                "1. In Chrome, open CATS Information, Additional Board Information and Pool Lineup (tap each one).\n\n" +
                    "2. Long-press any text → Select all → Share → Railway Time board.\n\n" +
                    "Or take a screenshot with those open → Share → Railway Time board.",
            )
            .setPositiveButton("Open IVRM in Chrome") { _, _ ->
                val uri = Uri.parse(Store(this).settings.ivrmUrl)
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.chrome")) }
                    .onFailure { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Sign-in steps and the last page text, to send over if something doesn't work. No passwords or cookies. */
    private fun shareDebug() {
        val store = Store(this)
        val text = buildString {
            appendLine("Railway Time ${packageManager.getPackageInfo(packageName, 0).versionName} · Android ${android.os.Build.VERSION.RELEASE}")
            appendLine("Board page: ${CnWeb.safeUrl(store.boardUrl)} · status ${store.status} ${store.statusDetail.orEmpty()}")
            appendLine()
            appendLine("--- log ---")
            store.log.forEach { appendLine(it) }
            appendLine()
            appendLine("--- last page text ---")
            append(store.pageText ?: "(none)")
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share debug info"))
    }

    private companion object {
        const val PICK_IMAGE = 1
    }
}
