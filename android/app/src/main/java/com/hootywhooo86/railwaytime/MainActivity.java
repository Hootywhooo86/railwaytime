package com.hootywhooo86.railwaytime;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(PhoneSounds.class);
        registerPlugin(Ringer.class);
        super.onCreate(savedInstanceState);
        wakeForRing(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        wakeForRing(intent);
    }

    /** Opened by a ringing alarm: show over the lock screen and turn the screen on. */
    private void wakeForRing(Intent intent) {
        if (intent == null || !intent.getBooleanExtra("pixtimeRing", false)) return;
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
    }
}
