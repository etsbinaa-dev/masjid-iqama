package org.masjid.iqama;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.app.ActivityCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
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
        IqamaAlarms.replaceAll(getContext(), alarms, call.getArray("variants"));
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

    /** هل يسمح الجوال بقراءة الموقع والتطبيق مغلق ("السماح طوال الوقت")؟ */
    @PluginMethod
    public void bgLocationStatus(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", LocationCheck.allowed(getContext()));
        call.resolve(r);
    }

    /**
     * يطلب إذن "السماح طوال الوقت": في أندرويد 11 وما بعده يفتح الجوال صفحة إذن الموقع
     * فيختار الشخص "السماح طوال الوقت"؛ وإن لم تُفتح نفتح صفحة إعدادات التطبيق.
     */
    @PluginMethod
    public void requestBgLocation(PluginCall call) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // أول مرة (أو إن سمح الجوال بالسؤال مجدداً) نطلب الإذن؛ بعد الرفض المتكرر لا يظهر الطلب فنفتح الإعدادات
                android.content.SharedPreferences sp = getContext().getSharedPreferences("iqama_alarms", 0);
                if (ActivityCompat.shouldShowRequestPermissionRationale(getActivity(), Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    || !sp.getBoolean("bgAsked", false)) {
                    sp.edit().putBoolean("bgAsked", true).apply();
                    ActivityCompat.requestPermissions(getActivity(),
                        new String[] { Manifest.permission.ACCESS_BACKGROUND_LOCATION }, 4242);
                } else {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getContext().getPackageName(), null));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(i);
                }
            }
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
