package org.masjid.iqama;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * الصامت في المسجد: عند وقت إقامة مجموعة من المساجد يأخذ موقع الجوال (والتطبيق مغلق)،
 * فإن كان داخل أحدها (في حدود ~100 م مع هامش لخطأ الموقع) يضع الجوال على الصامت دقائق معدودة ثم يرجعه كما كان.
 * الصامت الكامل يحتاج إذن «عدم الإزعاج»؛ دونه نكتفي بالاهتزاز. ونتيجة كل فحص تُحفظ لتظهر في الإعدادات.
 */
final class SilentMode {

    interface Done { void with(JSONObject result); }

    private static final String PREFS = "iqama_silent";
    private static final float NEAR_M = 100f;
    // داخل المبنى قد يكون خطأ الموقع كبيراً؛ نتسامح معه حتى 150 م
    private static final float MAX_SLACK_M = 150f;
    private static final long WAIT_MS = 12000;
    private static final long MAX_AGE_MS = 10 * 60 * 1000L;
    static final int RESTORE_ID = 3999;

    private SilentMode() {}

    static boolean policyGranted(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.isNotificationPolicyAccessGranted();
    }

    private static boolean fgLocation(Context c) {
        return ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * a = {mins, mosques:[[lat,lng],...]}. background = الفحص المجدول والتطبيق مغلق (يحتاج الموقع «طوال الوقت»)،
     * وإلا فهو «اختبر الآن» من داخل التطبيق. done يُستدعى دائماً بالنتيجة.
     */
    @SuppressLint("MissingPermission")
    static void check(Context c, JSONObject a, boolean background, Done done) {
        if (background ? !LocationCheck.allowed(c) : !fgLocation(c)) {
            finish(c, done, result("noperm", -1, -1, null, background));
            return;
        }
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            finish(c, done, result("noloc", -1, -1, null, background));
            return;
        }
        final boolean[] fired = { false };
        final CancellationSignal cancel = new CancellationSignal();
        class Got {
            void with(Location loc) {
                synchronized (fired) {
                    if (fired[0]) return;
                    fired[0] = true;
                }
                JSONObject r;
                try {
                    if (loc == null) loc = lastKnown(lm);
                    if (loc == null) {
                        r = result("noloc", -1, -1, null, background);
                    } else {
                        float acc = loc.hasAccuracy() ? loc.getAccuracy() : 0f;
                        float d = nearest(loc, a.optJSONArray("mosques"));
                        if (d < 0 || d > NEAR_M + Math.min(MAX_SLACK_M, acc)) {
                            r = result("far", d, acc, null, background);
                        } else {
                            String mode = silence(c, a.optInt("mins", 10));
                            r = result(mode == null ? "already" : "silenced", d, acc, mode, background);
                        }
                    }
                } catch (Exception e) {
                    r = result("error", -1, -1, null, background);
                }
                finish(c, done, r);
            }
        }
        Got got = new Got();
        // داخل المبنى قد يضعف GPS، فنبدأ بموقع الشبكة إن وُجد
        String provider = null;
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) provider = LocationManager.NETWORK_PROVIDER;
        else if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) provider = LocationManager.GPS_PROVIDER;
        if (provider != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                lm.getCurrentLocation(provider, cancel, ContextCompat.getMainExecutor(c), got::with);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    cancel.cancel();
                    got.with(null);
                }, WAIT_MS);
                return;
            } catch (Exception ignored) {
            }
        }
        got.with(null);
    }

    private static JSONObject result(String outcome, float dist, float acc, String mode, boolean background) {
        JSONObject r = new JSONObject();
        try {
            r.put("at", System.currentTimeMillis());
            r.put("outcome", outcome);
            r.put("dist", Math.round(dist));
            r.put("acc", Math.round(acc));
            if (mode != null) r.put("mode", mode);
            r.put("test", !background);
        } catch (Exception ignored) {
        }
        return r;
    }

    private static void finish(Context c, Done done, JSONObject r) {
        c.getSharedPreferences(PREFS, 0).edit().putString("lastLog", r.toString()).apply();
        try {
            done.with(r);
        } catch (Exception ignored) {
        }
    }

    static JSONObject lastLog(Context c) {
        try {
            return new JSONObject(c.getSharedPreferences(PREFS, 0).getString("lastLog", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @SuppressLint("MissingPermission")
    private static Location lastKnown(LocationManager lm) {
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) {
        }
        if (best == null || System.currentTimeMillis() - best.getTime() > MAX_AGE_MS) return null;
        return best;
    }

    /** المسافة (م) إلى أقرب مسجد في القائمة، أو -1 إن كانت فارغة. */
    private static float nearest(Location here, JSONArray mosques) {
        if (mosques == null) return -1;
        float best = -1;
        float[] d = new float[1];
        for (int i = 0; i < mosques.length(); i++) {
            JSONArray m = mosques.optJSONArray(i);
            if (m == null || m.length() < 2) continue;
            Location.distanceBetween(here.getLatitude(), here.getLongitude(), m.optDouble(0), m.optDouble(1), d);
            if (best < 0 || d[0] < best) best = d[0];
        }
        return best;
    }

    /**
     * يضع الجوال على الصامت (من الرنين أو الاهتزاز) إن كان إذن «عدم الإزعاج» مفعّلاً، وإلا فعلى الاهتزاز (من الرنين)،
     * ويجدول الرجوع بعد mins دقيقة. يرجع "silent" أو "vibrate"، أو null إن لم يتغيّر شيء (صامت أصلاً).
     */
    private static String silence(Context c, int mins) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return null;
        int before = am.getRingerMode();
        boolean dnd = policyGranted(c);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        int filterBefore = nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? nm.getCurrentInterruptionFilter() : -1;
        int target;
        if (before == AudioManager.RINGER_MODE_SILENT) return null;
        else if (dnd) target = AudioManager.RINGER_MODE_SILENT;
        else if (before == AudioManager.RINGER_MODE_NORMAL) target = AudioManager.RINGER_MODE_VIBRATE;
        else return null;   // على الاهتزاز أصلاً ولا إذن للصامت الكامل
        try {
            am.setRingerMode(target);
        } catch (SecurityException e) {
            if (before != AudioManager.RINGER_MODE_NORMAL) return null;
            target = AudioManager.RINGER_MODE_VIBRATE;
            try {
                am.setRingerMode(target);
            } catch (Exception ignored) {
                return null;
            }
        }
        // نحفظ وضع الصوت ووضع «عدم الإزعاج» قبل التغيير (سامسونج يفعّل «عدم الإزعاج» مع الصامت الكامل)، وموعد الإرجاع
        long until = System.currentTimeMillis() + Math.max(1, mins) * 60_000L;
        c.getSharedPreferences(PREFS, 0).edit().putInt("before", before).putInt("set", target)
            .putInt("filter", filterBefore).putLong("until", until).apply();
        try {
            JSONObject r = new JSONObject();
            r.put("id", RESTORE_ID);
            r.put("silentRestore", true);
            r.put("repeat", false);
            IqamaAlarms.setAlarm(c, r, until);
        } catch (Exception ignored) {
        }
        return target == AudioManager.RINGER_MODE_SILENT ? "silent" : "vibrate";
    }

    /** يرجع وضع «عدم الإزعاج» ووضع الصوت كما كانا قبل الصامت. */
    static void restore(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, 0);
        if (!sp.contains("before")) return;
        int before = sp.getInt("before", -1), filter = sp.getInt("filter", -1);
        sp.edit().remove("before").remove("set").remove("filter").remove("until").apply();
        // أولاً «عدم الإزعاج»: إن فعّله النظام مع الصامت نرجعه كما كان
        if (filter > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && policyGranted(c)) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            try {
                if (nm != null && nm.getCurrentInterruptionFilter() != filter) nm.setInterruptionFilter(filter);
            } catch (Exception ignored) {
            }
        }
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null || before < 0) return;
        try {
            if (am.getRingerMode() != before) am.setRingerMode(before);
        } catch (Exception ignored) {
        }
    }

    /** شبكة أمان: إن فات موعد الإرجاع لأي سبب (أُعيد تشغيل الجوال، أو لم يصل التنبيه) نرجع الصوت الآن. */
    static void restoreIfDue(Context c) {
        long until = c.getSharedPreferences(PREFS, 0).getLong("until", 0);
        if (until > 0 && System.currentTimeMillis() >= until) restore(c);
    }
}
