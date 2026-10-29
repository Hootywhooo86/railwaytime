package com.hootywhooo86.railwaytime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Android forgets alarm-clock alarms on reboot, update or clock change; put them back. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        AlarmStore.armAll(c);
    }
}
