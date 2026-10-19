package app.railwaytime

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Nap Stop: the railtime alarm clock. Alarms go through Android's alarm-clock system
 * (setAlarmClock) — the same one the Clock app uses — so they ring on time with the screen off,
 * in deep sleep and with the app closed, and show the ⏰ in the status bar.
 */
object NapAlarm {
    const val ACTION_FIRE = "app.railwaytime.NAP_FIRE"
    const val ACTION_DONE = "app.railwaytime.NAP_DONE"
    const val SNOOZE_MIN = 10

    private fun firePending(context: Context) = PendingIntent.getBroadcast(
        context, 100, Intent(context, NapReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun showPending(context: Context) = PendingIntent.getActivity(
        context, 101, Intent(context, NapActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Sets the one Nap Stop alarm (replacing any other). Returns false if Android refused it. */
    fun set(context: Context, atMillis: Long): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        val ok = runCatching {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, showPending(context)), firePending(context))
        }.onFailure {
            Store(context).log("nap: alarm refused (${it.javaClass.simpleName})")
        }.isSuccess
        if (ok) {
            Store(context).napAt = atMillis
            Store(context).log("nap: set")
            Widget.updateAll(context)
        }
        return ok
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(firePending(context))
        Store(context).napAt = 0
        Widget.updateAll(context)
    }

    /** After a reboot alarms are gone: set it again if it's still ahead. */
    fun restore(context: Context) {
        val at = Store(context).napAt
        if (at > System.currentTimeMillis()) set(context, at) else if (at != 0L) cancel(context)
    }
}

class NapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NapAlarm.ACTION_FIRE) return
        Store(context).napAt = 0
        context.startForegroundService(Intent(context, NapRingService::class.java).setAction(NapRingService.ACTION_RING))
    }
}

/**
 * Rings the alarm: horn (or the phone's alarm sound), vibration, and a full-screen "ALL ABOARD!"
 * that wakes the screen over the lock screen. Stops by itself after 10 minutes.
 */
class NapRingService : Service() {
    private var horn: HornPlayer? = null
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wake: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private val timeout = Runnable { finish(snooze = false) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> ring()
            ACTION_SNOOZE -> finish(snooze = true)
            ACTION_DISMISS -> finish(snooze = false)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun ring() {
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
        if (horn != null || player != null) return
        Store(this).log("nap: ringing")
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "railwaytime:nap").apply { acquire(RING_MS + 5_000) }

        if (Store(this).napSound == Store.NapSound.HORN) {
            horn = HornPlayer().also { it.start(loop = true) }
        } else {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            player = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                    setDataSource(this@NapRingService, uri)
                    isLooping = true
                    prepare()
                    start()
                }
            }.getOrElse { HornPlayer().also { h -> h.start(loop = true); horn = h }; null }
        }

        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 900, 300, 900, 300, 300, 300, 1200, 1400), 0),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
        )
        main.postDelayed(timeout, RING_MS)
    }

    private fun finish(snooze: Boolean) {
        main.removeCallbacks(timeout)
        horn?.stop(); horn = null
        player?.run { runCatching { stop() }; release() }; player = null
        vibrator?.cancel(); vibrator = null
        wake?.takeIf { it.isHeld }?.release(); wake = null
        if (snooze) {
            NapAlarm.set(this, System.currentTimeMillis() + NapAlarm.SNOOZE_MIN * 60_000L)
            Store(this).log("nap: snoozed ${NapAlarm.SNOOZE_MIN} min")
        } else {
            Store(this).log("nap: done")
        }
        sendBroadcast(Intent(NapAlarm.ACTION_DONE).setPackage(packageName))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Nap Stop alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Your Nap Stop alarm ringing"
                setSound(null, null) // the service plays the sound itself
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        val full = PendingIntent.getActivity(
            this, 200, Intent(this, NapRingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(label: String, act: String, code: Int) = Notification.Action.Builder(
            null, label,
            PendingIntent.getService(
                this, code, Intent(this, NapRingService::class.java).setAction(act),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_refresh)
            .setContentTitle("ALL ABOARD! 🚂")
            .setContentText("Nap Stop — end of the line.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(action("One more stop (+${NapAlarm.SNOOZE_MIN})", ACTION_SNOOZE, 201))
            .addAction(action("I'm on the train", ACTION_DISMISS, 202))
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        main.removeCallbacks(timeout)
        horn?.stop()
        player?.release()
        vibrator?.cancel()
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_RING = "app.railwaytime.NAP_RING"
        const val ACTION_SNOOZE = "app.railwaytime.NAP_SNOOZE"
        const val ACTION_DISMISS = "app.railwaytime.NAP_DISMISS"
        private const val CHANNEL = "nap"
        private const val NOTIFICATION_ID = 20
        private const val RING_MS = 10 * 60_000L

        fun send(context: Context, action: String) {
            context.startService(Intent(context, NapRingService::class.java).setAction(action))
        }
    }
}

/**
 * A synthesised locomotive horn: a five-chime chord with a chunky, 8-bit square-ish tone, sounded
 * in the railway crossing pattern — long, long, short, long.
 */
class HornPlayer {
    private var track: AudioTrack? = null

    fun start(loop: Boolean) {
        stop()
        val pcm = pattern(RATE)
        val t = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            pcm.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        t.write(pcm, 0, pcm.size)
        if (loop) t.setLoopPoints(0, pcm.size, -1)
        t.play()
        track = t
    }

    fun stop() {
        track?.run {
            runCatching { stop() }
            release()
        }
        track = null
    }

    companion object {
        private const val RATE = 22_050

        /** D#4 F#4 G#4 B4 D#5 — the chord of a five-chime horn. */
        private val CHORD = doubleArrayOf(311.13, 369.99, 415.30, 493.88, 622.25)

        fun pattern(rate: Int): ShortArray {
            // (seconds, sounding?) — long, long, short, long, then a breath before it repeats.
            val parts = listOf(1.1 to true, 0.25 to false, 1.1 to true, 0.25 to false, 0.45 to true, 0.25 to false, 1.6 to true, 1.3 to false)
            val out = ShortArray(parts.sumOf { (it.first * rate).toInt() })
            var at = 0
            for ((secs, on) in parts) {
                val n = (secs * rate).toInt()
                if (on) {
                    val attack = 0.03 * rate
                    val release = 0.08 * rate
                    for (i in 0 until n) {
                        val env = min(1.0, min(i / attack, (n - i) / release))
                        val t = i.toDouble() / rate
                        var v = 0.0
                        CHORD.forEachIndexed { k, f ->
                            val x = 2 * PI * f * (1 + k * 0.0015) * t
                            v += sin(x) + sin(3 * x) / 3 + sin(5 * x) / 5 // band-limited square: brassy, 8-bit
                        }
                        out[at + i] = (v * 0.13 * env * 32767 * 0.9).toInt().coerceIn(-32768, 32767).toShort()
                    }
                }
                at += n
            }
            return out
        }
    }
}
