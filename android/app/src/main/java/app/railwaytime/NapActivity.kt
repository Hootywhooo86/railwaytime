package app.railwaytime

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import app.railwaytime.core.DayTime
import app.railwaytime.core.NapTime
import app.railwaytime.core.RailClock
import java.time.Instant
import java.time.LocalTime

/** NAP STOP — set a railtime alarm for the nap after your call. */
class NapActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var preview: HornPlayer? = null
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nap)
        val store = Store(this)

        findViewById<PixelClockView>(R.id.nap_clock).timeText = {
            DayTime.clock(RailClock.railNow(store.settings, Instant.now()))
        }

        mapOf(R.id.nap_20 to 20, R.id.nap_30 to 30, R.id.nap_45 to 45, R.id.nap_60 to 60, R.id.nap_90 to 90)
            .forEach { (id, mins) -> findViewById<Button>(id).setOnClickListener { setIn(mins) } }

        findViewById<Button>(R.id.nap_pick).setOnClickListener {
            val rail = RailClock.railNow(store.settings, Instant.now()).plusMinutes(60)
            TimePickerDialog(this, android.R.style.Theme_DeviceDefault_Dialog, { _, h, m ->
                setAt(NapTime.nextRailtime(store.settings, Instant.now(), LocalTime.of(h, m)).toEpochMilli())
            }, rail.hour, rail.minute, true).show()
        }
        findViewById<Button>(R.id.nap_cancel).setOnClickListener {
            NapAlarm.cancel(this)
            Toast.makeText(this, "Stop cancelled", Toast.LENGTH_SHORT).show()
            render()
        }
        findViewById<Button>(R.id.nap_sound).setOnClickListener {
            store.napSound = if (store.napSound == Store.NapSound.HORN) Store.NapSound.PHONE else Store.NapSound.HORN
            render()
        }
        findViewById<Button>(R.id.nap_test).setOnClickListener {
            if (preview != null) stopPreview() else {
                preview = HornPlayer().also { it.start(loop = false) }
                findViewById<Button>(R.id.nap_test).text = "STOP HORN"
                handler.postDelayed(::stopPreview, 6_500)
            }
        }
        findViewById<Button>(R.id.nap_fix).setOnClickListener { fixPermissions() }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        stopPreview()
        super.onPause()
    }

    private fun stopPreview() {
        preview?.stop()
        preview = null
        findViewById<Button>(R.id.nap_test).text = "TEST HORN"
    }

    private fun setIn(minutes: Int) = setAt(System.currentTimeMillis() + minutes * 60_000L)

    private fun setAt(at: Long) {
        if (!NapAlarm.set(this, at)) {
            Toast.makeText(this, "Android wouldn't set the alarm — tap FIX ALARM SETTINGS", Toast.LENGTH_LONG).show()
            return
        }
        val rail = NapTime.railtimeOf(Store(this).settings, Instant.ofEpochMilli(at))
        Toast.makeText(this, "Next stop: ${DayTime.clock(rail)} 🚂", Toast.LENGTH_SHORT).show()
        if (missingPermissions().isNotEmpty()) fixPermissions()
        render()
    }

    private fun render() {
        val store = Store(this)
        val settings = store.settings
        val now = Instant.now()
        val at = store.napAt.takeIf { it > System.currentTimeMillis() }
        val next = findViewById<TextView>(R.id.nap_next)
        val sub = findViewById<TextView>(R.id.nap_next_sub)
        if (at != null) {
            val rail = NapTime.railtimeOf(settings, Instant.ofEpochMilli(at))
            val railNow = RailClock.railNow(settings, now)
            next.text = DayTime.clock(rail)
            sub.text = "${DayTime.dayWord(railNow, rail)} · ${DayTime.until(railNow, rail)}"
            findViewById<View>(R.id.nap_cancel).visibility = View.VISIBLE
        } else {
            next.text = "--:--"
            sub.text = "No stop set. Pick one below."
            findViewById<View>(R.id.nap_cancel).visibility = View.GONE
        }
        findViewById<Button>(R.id.nap_sound).text =
            if (store.napSound == Store.NapSound.HORN) "SOUND: TRAIN HORN" else "SOUND: PHONE ALARM"
        val missing = missingPermissions()
        findViewById<TextView>(R.id.nap_warn).apply {
            visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
            text = "Needs: " + missing.joinToString(", ")
        }
        findViewById<View>(R.id.nap_fix).visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
    }

    /** What Android still has to allow for the alarm to ring and wake the screen. */
    private fun missingPermissions(): List<String> {
        val out = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) out += "notifications"
        if (Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            out += "full-screen alarm"
        }
        if (Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            out += "alarms"
        }
        return out
    }

    private fun fixPermissions() {
        when {
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager::class.java).canUseFullScreenIntent() ->
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms() ->
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
        if (missingPermissions().isNotEmpty()) fixPermissions()
    }
}
