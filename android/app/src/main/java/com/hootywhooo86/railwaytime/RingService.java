package com.hootywhooo86.railwaytime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import androidx.core.app.NotificationCompat;
import org.json.JSONObject;

/**
 * Rings an alarm or timer on the alarm stream, over and over, until it's stopped or snoozed
 * (or after 10 minutes, like a stock clock app). Runs as a foreground service so Android keeps it
 * alive while the screen is off and PixTime is closed.
 */
public class RingService extends Service {

    static final String ACTION_START = "start", ACTION_STOP = "stop", ACTION_SNOOZE = "snooze", ACTION_SILENCE = "silence";
    static final int NOTIF_ID = 4242;
    static final long TIMEOUT_MS = 10 * 60 * 1000;

    /** The ring that's going right now, if any (read by the app when it opens). */
    static volatile JSONObject current;
    static volatile Runnable onChange;

    private MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wake;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> finish("timeout");

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            JSONObject item;
            try { item = new JSONObject(intent.getStringExtra("item")); } catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
            if (current != null) finishQuietly("timeout"); // a new ring replaces one that's still going
            start(item);
        } else if (ACTION_STOP.equals(action)) {
            finish("stop");
        } else if (ACTION_SNOOZE.equals(action)) {
            snooze();
        } else if (ACTION_SILENCE.equals(action)) {
            finish(null); // the app handled it itself
        } else if (current == null) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void start(JSONObject item) {
        current = item;
        startForegroundCompat(buildNotification(item));
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pixtime:ring");
        wake.acquire(TIMEOUT_MS + 60000);
        play(item.optString("uri"));
        if (item.optBoolean("vibe", true)) vibrate();
        handler.postDelayed(timeout, TIMEOUT_MS);
        // Show the full-screen ring screen if PixTime is open; it can't steal the screen otherwise.
        Runnable r = onChange;
        if (r != null) r.run();
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIF_ID, n);
    }

    static String channelId(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        boolean dnd = Build.VERSION.SDK_INT >= 23 && nm.isNotificationPolicyAccessGranted();
        String id = dnd ? "pixtime_ringing_dnd" : "pixtime_ringing";
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(id) == null) {
            NotificationChannel ch = new NotificationChannel(id, "Ringing alarms & timers", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Shown while an alarm or timer is ringing");
            ch.setSound(null, null); // the service plays the sound itself, on a loop
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            ch.setBypassDnd(true);
            nm.createNotificationChannel(ch);
            nm.deleteNotificationChannel(dnd ? "pixtime_ringing" : "pixtime_ringing_dnd");
        }
        return id;
    }

    private PendingIntent self(String action, int code) {
        return PendingIntent.getService(this, code, new Intent(this, RingService.class).setAction(action), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification(JSONObject item) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("pixtimeRing", true);
        PendingIntent full = PendingIntent.getActivity(this, 7, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean timer = "timer".equals(item.optString("kind"));
        boolean challenge = item.optBoolean("challenge");
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, channelId(this))
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(0xFF2DD4BF)
            .setContentTitle(item.optString("title", timer ? "⏳ Time's up!" : "⏰ Alarm"))
            .setContentText(item.optString("body"))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(full)
            .setFullScreenIntent(full, true);
        if (!timer && item.optInt("snoozesLeft", 99) > 0) b.addAction(0, "Snooze " + item.optInt("snoozeMin", 10) + " min", self(ACTION_SNOOZE, 2));
        if (challenge) b.addAction(0, "Open to turn off", full);
        else b.addAction(0, timer ? "Dismiss" : "Stop", self(ACTION_STOP, 3));
        return b.build();
    }

    private Uri resolve(String uri) {
        if (uri == null || uri.isEmpty()) return null;
        if (uri.startsWith("raw:")) return Uri.parse("android.resource://" + getPackageName() + "/raw/" + uri.substring(4));
        return Uri.parse(uri);
    }

    private void play(String uri) {
        // Try the chosen sound, then the phone's default alarm, then PixTime's own beep.
        Uri[] tries = { resolve(uri), RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), resolve("raw:classic_beep") };
        for (Uri u : tries) {
            if (u == null) continue;
            try {
                MediaPlayer p = new MediaPlayer();
                p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                p.setDataSource(this, u);
                p.setLooping(true);
                p.prepare();
                p.start();
                player = p;
                return;
            } catch (Exception e) {
                // fall through to the next sound
            }
        }
    }

    private void vibrate() {
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        long[] pattern = { 0, 700, 500 };
        AudioAttributes aa = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build();
        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), aa);
        else vibrator.vibrate(pattern, 0, aa);
    }

    private void snooze() {
        JSONObject item = current;
        if (item == null) { stopSelf(); return; }
        try {
            int left = item.optInt("snoozesLeft", 99) - 1;
            long at = System.currentTimeMillis() + item.optInt("snoozeMin", 10) * 60000L;
            JSONObject next = new JSONObject(item.toString());
            next.put("key", item.optString("key") + "_z" + at).put("at", at).put("snoozesLeft", left);
            next.put("body", "Snoozed alarm");
            AlarmStore.add(this, next); // rings again even if PixTime stays closed
        } catch (Exception ignored) {}
        finish("snooze");
    }

    private void finishQuietly(String event) {
        JSONObject item = current;
        if (item != null && event != null) AlarmStore.addEvent(this, event, item);
        handler.removeCallbacks(timeout);
        if (player != null) { try { player.stop(); } catch (Exception ignored) {} player.release(); player = null; }
        if (vibrator != null) { vibrator.cancel(); vibrator = null; }
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
        current = null;
    }

    private void finish(String event) {
        finishQuietly(event);
        stopForeground(true);
        stopSelf();
        Runnable r = onChange;
        if (r != null) r.run();
    }

    @Override
    public void onDestroy() {
        if (current != null) finishQuietly("timeout");
        super.onDestroy();
    }
}
