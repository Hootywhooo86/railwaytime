package com.hootywhooo86.railwaytime;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.JSONArray;
import org.json.JSONObject;

/** The web app's handle on the native alarm engine. */
@CapacitorPlugin(name = "Ringer")
public class Ringer extends Plugin {

    @Override
    public void load() {
        RingService.onChange = () -> {
            if (getBridge() == null) return;
            getActivity().runOnUiThread(() -> notifyListeners("change", new JSObject()));
        };
    }

    /** Replaces every planned ring. items: [{key, at, kind, aid|tid, title, body, uri, vibe, snoozeMin, snoozesLeft, challenge}] */
    @PluginMethod
    public void schedule(PluginCall call) {
        JSArray items = call.getArray("items", new JSArray());
        try {
            AlarmStore.replaceAll(getContext(), new JSONArray(items.toString()));
            RingService.channelId(getContext());
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not schedule alarms: " + e.getMessage());
        }
    }

    @PluginMethod
    public void current(PluginCall call) {
        JSObject r = new JSObject();
        JSONObject c = RingService.current;
        if (c != null) {
            try { r.put("item", JSObject.fromJSONObject(c)); } catch (Exception ignored) {}
        }
        call.resolve(r);
    }

    /** Stops the ringing because the app took care of it (STOP, snooze or a solved challenge in the app). */
    @PluginMethod
    public void silence(PluginCall call) {
        if (RingService.current != null) getContext().startService(new Intent(getContext(), RingService.class).setAction(RingService.ACTION_SILENCE));
        call.resolve();
    }

    @PluginMethod
    public void drainEvents(PluginCall call) {
        JSObject r = new JSObject();
        try { r.put("events", new JSArray(AlarmStore.drainEvents(getContext()).toString())); } catch (Exception e) { r.put("events", new JSArray()); }
        call.resolve(r);
    }

    /** Android 14+ asks the user before an app may show alarms full screen over the lock screen. */
    @PluginMethod
    public void fullScreenStatus(PluginCall call) {
        JSObject r = new JSObject();
        boolean ok = true;
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
            ok = nm.canUseFullScreenIntent();
        }
        r.put("granted", ok);
        call.resolve(r);
    }

    @PluginMethod
    public void openFullScreenSettings(PluginCall call) {
        Intent i = Build.VERSION.SDK_INT >= 34
            ? new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getContext().getPackageName()))
            : new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getContext().getPackageName());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { getContext().startActivity(i); } catch (Exception e) {
            getContext().startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        RingService.onChange = null;
    }
}
