package org.masjid.iqama;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // نظام التنبيه الخاص بالتطبيق (يشغّل الصوت على صوت المنبّه)
        registerPlugin(IqamaAlarmPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
