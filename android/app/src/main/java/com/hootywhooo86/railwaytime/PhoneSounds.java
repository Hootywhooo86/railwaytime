package com.hootywhooo86.railwaytime;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Lets Railway Time use every alarm, ringtone and notification sound on the phone (plus any
 * audio file), and makes alarm channels that play on the alarm stream and break through
 * Do Not Disturb once the user allows it.
 */
@CapacitorPlugin(
    name = "PhoneSounds",
    permissions = {
        @Permission(alias = "audio", strings = { Manifest.permission.READ_MEDIA_AUDIO }),
        @Permission(alias = "storage", strings = { Manifest.permission.READ_EXTERNAL_STORAGE })
    }
)
public class PhoneSounds extends Plugin {

    private MediaPlayer player;

    private String audioAlias() {
        return Build.VERSION.SDK_INT >= 33 ? "audio" : "storage";
    }

    @PluginMethod
    public void list(PluginCall call) {
        // Built-in sounds are always readable; ones the user added need media access.
        if (getPermissionState(audioAlias()) != PermissionState.GRANTED && call.getBoolean("ask", true)) {
            requestPermissionForAlias(audioAlias(), call, "listAfterPermission");
            return;
        }
        listSounds(call);
    }

    @PermissionCallback
    private void listAfterPermission(PluginCall call) {
        listSounds(call);
    }

    private void listSounds(PluginCall call) {
        JSArray out = new JSArray();
        int[][] kinds = { { RingtoneManager.TYPE_ALARM }, { RingtoneManager.TYPE_RINGTONE }, { RingtoneManager.TYPE_NOTIFICATION } };
        String[] names = { "alarm", "ringtone", "notification" };
        for (int i = 0; i < kinds.length; i++) {
            try {
                RingtoneManager rm = new RingtoneManager(getActivity());
                rm.setType(kinds[i][0]);
                Cursor c = rm.getCursor();
                while (c.moveToNext()) {
                    JSObject o = new JSObject();
                    o.put("title", c.getString(RingtoneManager.TITLE_COLUMN_INDEX));
                    o.put("uri", rm.getRingtoneUri(c.getPosition()).toString());
                    o.put("type", names[i]);
                    out.put(o);
                }
            } catch (Exception e) {
                // Some phones hide a category; keep the others.
            }
        }
        JSObject r = new JSObject();
        r.put("sounds", out);
        r.put("fullAccess", getPermissionState(audioAlias()) == PermissionState.GRANTED);
        call.resolve(r);
    }

    /** Pick any audio file (music, voice memo...). It's copied into the phone's Alarms folder so Android can always play it. */
    @PluginMethod
    public void pickFile(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        startActivityForResult(call, i, "picked");
    }

    @ActivityCallback
    private void picked(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            call.reject("cancelled", "CANCELLED");
            return;
        }
        Uri src = result.getData().getData();
        ContentResolver cr = getContext().getContentResolver();
        String title = "My sound";
        try (Cursor c = cr.query(src, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) title = c.getString(0);
        } catch (Exception ignored) {}
        String mime = cr.getType(src);
        try {
            Uri dest;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Audio.Media.DISPLAY_NAME, title);
                v.put(MediaStore.Audio.Media.TITLE, title.replaceAll("\\.[^.]+$", ""));
                v.put(MediaStore.Audio.Media.MIME_TYPE, mime != null ? mime : "audio/mpeg");
                v.put(MediaStore.Audio.Media.RELATIVE_PATH, "Alarms/Railway Time");
                v.put(MediaStore.Audio.Media.IS_ALARM, 1);
                dest = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
                if (dest == null) throw new Exception("Could not save the sound");
                try (InputStream in = cr.openInputStream(src); OutputStream os = cr.openOutputStream(dest)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            } else {
                cr.takePersistableUriPermission(src, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                dest = src;
            }
            JSObject r = new JSObject();
            r.put("title", title.replaceAll("\\.[^.]+$", ""));
            r.put("uri", dest.toString());
            r.put("type", "file");
            call.resolve(r);
        } catch (Exception e) {
            call.reject("Couldn't use that file: " + e.getMessage());
        }
    }

    private Uri resolve(String uri) {
        if (uri.startsWith("raw:")) return Uri.parse("android.resource://" + getContext().getPackageName() + "/raw/" + uri.substring(4));
        return Uri.parse(uri);
    }

    /** Play a sound on the alarm stream. loop=true while ringing, false for a preview. */
    @PluginMethod
    public void play(PluginCall call) {
        stopPlayer();
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(getContext(), resolve(call.getString("uri", "")));
            player.setLooping(call.getBoolean("loop", false));
            player.prepare();
            player.start();
            call.resolve();
        } catch (Exception e) {
            stopPlayer();
            call.reject("Couldn't play that sound");
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        stopPlayer();
        call.resolve();
    }

    private void stopPlayer() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
    }

    private NotificationManager nm() {
        return (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @PluginMethod
    public void dndStatus(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", Build.VERSION.SDK_INT < 23 || nm().isNotificationPolicyAccessGranted());
        call.resolve(r);
    }

    @PluginMethod
    public void openDndSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    /**
     * Makes (or reuses) an alarm channel for one sound. A channel's sound and DND setting are
     * fixed once created, so the id carries both; old channels are removed by prune().
     */
    @PluginMethod
    public void ensureChannel(PluginCall call) {
        String key = call.getString("key", "default").replaceAll("[^a-zA-Z0-9_]", "_");
        String uri = call.getString("uri", "");
        boolean dnd = Build.VERSION.SDK_INT >= 23 && nm().isNotificationPolicyAccessGranted();
        String id = "rt_" + key + "_" + Integer.toHexString(uri.hashCode()) + (dnd ? "_dnd" : "");
        JSObject r = new JSObject();
        r.put("id", id);
        if (Build.VERSION.SDK_INT >= 26 && nm().getNotificationChannel(id) == null) {
            NotificationChannel ch = new NotificationChannel(id, "Alarm: " + call.getString("name", "Sound"), NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Alarms and timers with this sound");
            ch.setSound(resolve(uri), new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[] { 0, 600, 300, 600, 300, 600 });
            ch.enableLights(true);
            ch.setLightColor(0xFF2DD4BF);
            ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            ch.setBypassDnd(true); // honoured by Android only when DND access is granted
            nm().createNotificationChannel(ch);
        }
        call.resolve(r);
    }

    /** Deletes old alarm channels that are no longer used. */
    @PluginMethod
    public void prune(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 26) {
            JSArray keep = call.getArray("keep", new JSArray());
            java.util.Set<String> k = new java.util.HashSet<>();
            for (int i = 0; i < keep.length(); i++) k.add(keep.optString(i));
            for (NotificationChannel ch : nm().getNotificationChannels()) {
                String id = ch.getId();
                if ((id.startsWith("rt_") || id.startsWith("alarm_")) && !k.contains(id)) nm().deleteNotificationChannel(id);
            }
        }
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        stopPlayer();
    }
}
