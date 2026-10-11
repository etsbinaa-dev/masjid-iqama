package org.masjid.iqama;

import com.getcapacitor.JSArray;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.JSONObject;

/** الجسر بين شاشة التطبيق (JavaScript) ونظام التنبيه. */
@CapacitorPlugin(name = "IqamaAlarm")
public class IqamaAlarmPlugin extends Plugin {

    @PluginMethod
    public void schedule(PluginCall call) {
        JSArray alarms = call.getArray("alarms");
        if (alarms == null) {
            call.reject("alarms missing");
            return;
        }
        IqamaAlarms.replaceAll(getContext(), alarms);
        call.resolve();
    }

    @PluginMethod
    public void cancelAll(PluginCall call) {
        IqamaAlarms.cancelAll(getContext());
        call.resolve();
    }

    @PluginMethod
    public void test(PluginCall call) {
        try {
            JSONObject a = new JSONObject();
            a.put("id", 99);
            a.put("title", call.getString("title", "تجربة التنبيه"));
            a.put("body", call.getString("body", ""));
            a.put("repeat", false);
            long at = System.currentTimeMillis() + call.getInt("seconds", 5) * 1000L;
            IqamaAlarms.setAlarm(getContext(), a, at);
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void stopSound(PluginCall call) {
        AlarmReceiver.stopSound();
        call.resolve();
    }
}
