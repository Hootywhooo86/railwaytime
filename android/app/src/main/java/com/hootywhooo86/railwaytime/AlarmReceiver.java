package com.hootywhooo86.railwaytime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.core.content.ContextCompat;

/** Fired by Android's alarm clock at ring time; starts the ring service. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (!AlarmStore.ACTION_RING.equals(intent.getAction())) return;
        String item = intent.getStringExtra("item");
        if (item == null) return;
        try {
            AlarmStore.remove(c, new org.json.JSONObject(item).optString("key"));
        } catch (Exception ignored) {}
        Intent s = new Intent(c, RingService.class).setAction(RingService.ACTION_START).putExtra("item", item);
        ContextCompat.startForegroundService(c, s);
    }
}
