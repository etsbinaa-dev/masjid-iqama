package org.masjid.iqama;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * قبل كل صلاة بساعة: يأخذ موقع الجوال (والتطبيق مغلق) ويختار أقرب مسجد،
 * ثم يضبط تنبيه تلك الصلاة على وقت ذلك المسجد.
 * يحتاج إذن الموقع "السماح طوال الوقت"؛ دونه يبقى التنبيه على آخر مسجد اختاره التطبيق.
 */
final class LocationCheck {

    private static final long WAIT_MS = 8000;
    // موقع أقدم من 3 ساعات لا نعتمد عليه
    private static final long MAX_AGE_MS = 3 * 60 * 60 * 1000L;

    private LocationCheck() {}

    static boolean allowed(Context c) {
        boolean fg = granted(c, Manifest.permission.ACCESS_COARSE_LOCATION)
            || granted(c, Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return fg;
        return fg && granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    private static boolean granted(Context c, String p) {
        return ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED;
    }

    /** slot = رقم تنبيه الصلاة (1 الفجر، 3 العصر، ...). */
    @SuppressLint("MissingPermission")
    static void run(Context c, int slot, BroadcastReceiver.PendingResult pr) {
        if (!allowed(c)) {
            pr.finish();
            return;
        }
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            pr.finish();
            return;
        }
        final boolean[] done = { false };
        final Handler handler = new Handler(Looper.getMainLooper());
        final CancellationSignal cancel = new CancellationSignal();
        class Finish {
            void with(Location loc) {
                synchronized (done) {
                    if (done[0]) return;
                    done[0] = true;
                }
                try {
                    if (loc == null) loc = lastKnown(lm);
                    if (loc != null) apply(c, slot, loc);
                } catch (Exception ignored) {
                } finally {
                    pr.finish();
                }
            }
        }
        Finish finish = new Finish();
        String provider = null;
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) provider = LocationManager.NETWORK_PROVIDER;
        else if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) provider = LocationManager.GPS_PROVIDER;
        if (provider != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                lm.getCurrentLocation(provider, cancel, ContextCompat.getMainExecutor(c), finish::with);
                handler.postDelayed(() -> {
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

    /** يختار أقرب مسجد ويضبط تنبيه هذه الصلاة على وقته. */
    private static void apply(Context c, int slot, Location here) {
        JSONArray variants = IqamaAlarms.loadVariants(c);
        JSONObject best = null;
        float bestD = Float.MAX_VALUE;
        float[] d = new float[1];
        for (int i = 0; i < variants.length(); i++) {
            JSONObject v = variants.optJSONObject(i);
            if (v == null || !v.has("lat") || !v.has("lng")) continue;
            Location.distanceBetween(here.getLatitude(), here.getLongitude(), v.optDouble("lat"), v.optDouble("lng"), d);
            if (d[0] < bestD) {
                bestD = d[0];
                best = v;
            }
        }
        if (best == null) return;
        JSONArray alarms = best.optJSONArray("alarms");
        if (alarms == null) return;
        for (int i = 0; i < alarms.length(); i++) {
            JSONObject a = alarms.optJSONObject(i);
            if (a != null && a.optInt("id") == slot) {
                IqamaAlarms.updateOne(c, a);
                return;
            }
        }
    }
}
