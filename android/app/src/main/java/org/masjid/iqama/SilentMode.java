package org.masjid.iqama;

import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
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
 * فإن كان داخل أحدها (في حدود ~100 م) يضع الجوال على الصامت دقائق معدودة ثم يرجعه كما كان.
 * الصامت الكامل يحتاج إذن «عدم الإزعاج»؛ دونه نكتفي بالاهتزاز.
 */
final class SilentMode {

    private static final String PREFS = "iqama_silent";
    private static final float NEAR_M = 100f;
    private static final long WAIT_MS = 10000;
    private static final long MAX_AGE_MS = 10 * 60 * 1000L;
    static final int RESTORE_ID = 3999;

    private SilentMode() {}

    static boolean policyGranted(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.isNotificationPolicyAccessGranted();
    }

    /** a = {silent:true, mins, mosques:[[lat,lng],...]} */
    @SuppressLint("MissingPermission")
    static void run(Context c, JSONObject a, BroadcastReceiver.PendingResult pr) {
        if (!LocationCheck.allowed(c)) {
            pr.finish();
            return;
        }
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            pr.finish();
            return;
        }
        final boolean[] done = { false };
        final CancellationSignal cancel = new CancellationSignal();
        class Finish {
            void with(Location loc) {
                synchronized (done) {
                    if (done[0]) return;
                    done[0] = true;
                }
                try {
                    if (loc == null) loc = lastKnown(lm);
                    if (loc != null && insideAny(loc, a.optJSONArray("mosques"))) silence(c, a.optInt("mins", 10));
                } catch (Exception ignored) {
                } finally {
                    pr.finish();
                }
            }
        }
        Finish finish = new Finish();
        // داخل المبنى قد يضعف GPS، فنبدأ بموقع الشبكة إن وُجد
        String provider = null;
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) provider = LocationManager.NETWORK_PROVIDER;
        else if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) provider = LocationManager.GPS_PROVIDER;
        if (provider != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                lm.getCurrentLocation(provider, cancel, ContextCompat.getMainExecutor(c), finish::with);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    cancel.cancel();
                    finish.with(null);
                }, WAIT_MS);
                return;
            } catch (Exception ignored) {
            }
        }
        finish.with(null);
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

    private static boolean insideAny(Location here, JSONArray mosques) {
        if (mosques == null) return false;
        float[] d = new float[1];
        // نسمح بجزء من خطأ الموقع (حتى 50 م إضافية) لأن الموقع داخل المسجد أقل دقة
        float slack = Math.min(50f, here.hasAccuracy() ? here.getAccuracy() / 2f : 0f);
        for (int i = 0; i < mosques.length(); i++) {
            JSONArray m = mosques.optJSONArray(i);
            if (m == null || m.length() < 2) continue;
            Location.distanceBetween(here.getLatitude(), here.getLongitude(), m.optDouble(0), m.optDouble(1), d);
            if (d[0] <= NEAR_M + slack) return true;
        }
        return false;
    }

    /** يضع الجوال على الصامت (أو الاهتزاز دون إذن «عدم الإزعاج») ويجدول الرجوع بعد mins دقيقة. */
    private static void silence(Context c, int mins) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        int before = am.getRingerMode();
        if (before != AudioManager.RINGER_MODE_NORMAL) return;   // صامت أو اهتزاز أصلاً: لا نغيّر شيئاً
        int target = policyGranted(c) ? AudioManager.RINGER_MODE_SILENT : AudioManager.RINGER_MODE_VIBRATE;
        try {
            am.setRingerMode(target);
        } catch (SecurityException e) {
            target = AudioManager.RINGER_MODE_VIBRATE;
            try {
                am.setRingerMode(target);
            } catch (Exception ignored) {
                return;
            }
        }
        c.getSharedPreferences(PREFS, 0).edit().putInt("before", before).putInt("set", target).apply();
        try {
            JSONObject r = new JSONObject();
            r.put("id", RESTORE_ID);
            r.put("silentRestore", true);
            r.put("repeat", false);
            IqamaAlarms.setAlarm(c, r, System.currentTimeMillis() + Math.max(1, mins) * 60_000L);
        } catch (Exception ignored) {
        }
    }

    /** يرجع وضع الصوت كما كان، إلا إذا غيّره صاحب الجوال بنفسه في الأثناء. */
    static void restore(Context c) {
        android.content.SharedPreferences sp = c.getSharedPreferences(PREFS, 0);
        int before = sp.getInt("before", -1), set = sp.getInt("set", -1);
        sp.edit().clear().apply();
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null || before < 0 || am.getRingerMode() != set) return;
        try {
            am.setRingerMode(before);
        } catch (Exception ignored) {
        }
    }
}
