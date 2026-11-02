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
            a.put("sound", call.getString("sound", "hayya"));
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

    /** يحفظ ملف صوت (من الواجهة، بصيغة base64) في مجلد sounds ليشغّله التنبيه والتطبيق مغلق. */
    @PluginMethod
    public void saveSound(PluginCall call) {
        String name = call.getString("name", "");
        String data = call.getString("data");
        // اسم بسيط فقط (حروف وأرقام ونقطة وشرطة)، لا مسارات
        if (data == null || !name.matches("[A-Za-z0-9_.-]{1,64}") || name.startsWith(".")) {
            call.reject("bad sound");
            return;
        }
        try {
            java.io.File f = AdhanService.soundFile(getContext(), name);
            java.io.File dir = f.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) throw new java.io.IOException("mkdir");
            java.io.File tmp = new java.io.File(dir, name + ".tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                out.write(android.util.Base64.decode(data, android.util.Base64.DEFAULT));
            }
            if (!tmp.renameTo(f)) throw new java.io.IOException("rename");
            JSObject r = new JSObject();
            r.put("size", f.length());
            call.resolve(r);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    /** حجم ملف صوت محفوظ (0 إن لم يوجد)، لتعرف الواجهة هل تحتاج نسخه مرة أخرى. */
    @PluginMethod
    public void soundInfo(PluginCall call) {
        String name = call.getString("name", "");
        JSObject r = new JSObject();
        java.io.File f = name.matches("[A-Za-z0-9_.-]{1,64}") ? AdhanService.soundFile(getContext(), name) : null;
        r.put("size", f != null && f.exists() ? f.length() : 0);
        call.resolve(r);
    }

    @PluginMethod
    public void stopSound(PluginCall call) {
        AlarmReceiver.stopSound();
        call.resolve();
    }
}
