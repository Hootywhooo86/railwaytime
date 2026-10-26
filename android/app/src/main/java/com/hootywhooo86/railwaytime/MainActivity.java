package com.hootywhooo86.railwaytime;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(PhoneSounds.class);
        super.onCreate(savedInstanceState);
    }
}
