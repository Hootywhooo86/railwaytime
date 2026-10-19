package app.railwaytime

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import app.railwaytime.core.DayTime
import app.railwaytime.core.NapTime
import app.railwaytime.core.RailClock
import java.time.Instant

/** ALL ABOARD! — the full-screen alarm, shown over the lock screen. */
class NapRingActivity : Activity() {
    private val done = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_nap_ring)

        val settings = Store(this).settings
        findViewById<PixelClockView>(R.id.ring_clock).apply {
            ringing = true
            timeText = { DayTime.clock(RailClock.railNow(settings, Instant.now())) }
        }
        findViewById<TextView>(R.id.ring_line).text = NapTime.WAKE_LINES.random()
        findViewById<Button>(R.id.ring_snooze).apply {
            text = "ONE MORE STOP\n+${NapAlarm.SNOOZE_MIN} MIN"
            setOnClickListener {
                NapRingService.send(this@NapRingActivity, NapRingService.ACTION_SNOOZE)
                finish()
            }
        }
        findViewById<Button>(R.id.ring_dismiss).setOnClickListener {
            NapRingService.send(this, NapRingService.ACTION_DISMISS)
            finish()
        }

        // Private to the app (the flag is ignored before Android 13, where the action is ours alone).
        @SuppressLint("InlinedApi")
        val flags = RECEIVER_NOT_EXPORTED
        registerReceiver(done, IntentFilter(NapAlarm.ACTION_DONE), flags)
    }

    override fun onDestroy() {
        unregisterReceiver(done)
        super.onDestroy()
    }
}
