package com.hootywhooo86.railwaytime;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Everything the alarm engine needs to work while PixTime is closed: the planned rings (so they
 * survive reboots) and a queue of what the user did from the notification (stop/snooze), which the
 * app reads the next time it opens.
 */
final class AlarmStore {

    private static final String PREFS = "pixtime_alarms";
    static final String ACTION_RING = "com.hootywhooo86.railwaytime.RING";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized JSONArray items(Context c) {
        try { return new JSONArray(prefs(c).getString("items", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private static synchronized void saveItems(Context c, JSONArray a) {
        prefs(c).edit().putString("items", a.toString()).apply();
    }

    /** Replaces every planned ring with this list and hands them to Android's alarm clock. */
    static synchronized void replaceAll(Context c, JSONArray list) {
        cancelAll(c);
        saveItems(c, list);
        armAll(c);
    }

    static synchronized void armAll(Context c) {
        JSONArray a = items(c), keep = new JSONArray();
        long now = System.currentTimeMillis();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optLong("at") < now - 60000) continue;
            keep.put(o);
            arm(c, o);
        }
        saveItems(c, keep);
    }

    /** Adds one ring (used for snoozes made from the notification while the app is closed). */
    static synchronized void add(Context c, JSONObject o) {
        JSONArray a = items(c);
        a.put(o);
        saveItems(c, a);
        arm(c, o);
    }

    static synchronized void remove(Context c, String key) {
        JSONArray a = items(c), keep = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !key.equals(o.optString("key"))) keep.put(o);
        }
        saveItems(c, keep);
    }

    private static PendingIntent pending(Context c, JSONObject o, int flags) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION_RING).putExtra("item", o.toString());
        return PendingIntent.getBroadcast(c, o.optString("key").hashCode(), i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void arm(Context c, JSONObject o) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        // setAlarmClock: exact, allowed in Doze, shows the alarm icon, and lets the ring service start from the background.
        Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        PendingIntent show = PendingIntent.getActivity(c, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(o.optLong("at"), show), pending(c, o, PendingIntent.FLAG_UPDATE_CURRENT));
    }

    private static void cancelAll(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        JSONArray a = items(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            PendingIntent p = pending(c, o, PendingIntent.FLAG_NO_CREATE);
            if (p != null) { am.cancel(p); p.cancel(); }
        }
    }

    static synchronized void addEvent(Context c, String action, JSONObject item) {
        try {
            JSONArray q = new JSONArray(prefs(c).getString("events", "[]"));
            JSONObject e = new JSONObject();
            e.put("action", action).put("time", System.currentTimeMillis()).put("item", item);
            q.put(e);
            while (q.length() > 100) q.remove(0);
            prefs(c).edit().putString("events", q.toString()).apply();
        } catch (Exception ignored) {}
    }

    static synchronized JSONArray drainEvents(Context c) {
        JSONArray q;
        try { q = new JSONArray(prefs(c).getString("events", "[]")); } catch (Exception e) { q = new JSONArray(); }
        prefs(c).edit().putString("events", "[]").apply();
        return q;
    }
}
