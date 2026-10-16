package org.masjid.iqama;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.Calendar;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * جدولة تنبيهات الإقامة بنفسنا عبر AlarmManager.
 * كل تنبيه: {id, hour, minute, weekday (0 = كل يوم، 1 = الأحد ... 7 = السبت), title, body}.
 * تُحفظ القائمة في SharedPreferences لإعادة الجدولة بعد إعادة تشغيل الجوال.
 */
public final class IqamaAlarms {

    static final String ACTION_FIRE = "org.masjid.iqama.IQAMA_ALARM";
    static final String EXTRA_ALARM = "alarm";
    private static final String PREFS = "iqama_alarms";
    private static final String KEY = "alarms";
    // لكل مسجد له موقع: تنبيهاته الجاهزة {lat, lng, alarms}، لاختيار الأقرب قبل كل صلاة بساعة
    private static final String KEY_VARIANTS = "variants";

    private IqamaAlarms() {}

    /** يلغي التنبيهات السابقة ويحفظ القائمة الجديدة ويجدولها. */
    static void replaceAll(Context c, JSONArray alarms, JSONArray variants) {
        cancelAll(c);
        prefs(c).edit()
            .putString(KEY, alarms.toString())
            .putString(KEY_VARIANTS, variants == null ? "[]" : variants.toString())
            .apply();
        scheduleAll(c);
    }

    /** يستبدل تنبيهاً واحداً (بنفس الرقم) ويجدوله؛ يُستعمل بعد اختيار أقرب مسجد. */
    static void updateOne(Context c, JSONObject item) {
        JSONArray alarms = load(c);
        for (int i = 0; i < alarms.length(); i++) {
            JSONObject a = alarms.optJSONObject(i);
            if (a != null && a.optInt("id") == item.optInt("id")) {
                try {
                    alarms.put(i, item);
                } catch (Exception ignored) {
                }
                prefs(c).edit().putString(KEY, alarms.toString()).apply();
                scheduleNext(c, item);
                return;
            }
        }
    }

    static JSONArray loadVariants(Context c) {
        try {
            return new JSONArray(prefs(c).getString(KEY_VARIANTS, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static void scheduleAll(Context c) {
        JSONArray alarms = load(c);
        for (int i = 0; i < alarms.length(); i++) {
            JSONObject a = alarms.optJSONObject(i);
            if (a != null) scheduleNext(c, a);
        }
    }

    static void cancelAll(Context c) {
        JSONArray alarms = load(c);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        for (int i = 0; i < alarms.length(); i++) {
            JSONObject a = alarms.optJSONObject(i);
            if (a != null && am != null) am.cancel(pendingIntent(c, a));
        }
        prefs(c).edit().remove(KEY).remove(KEY_VARIANTS).apply();
    }

    /** يجدول الموعد القادم لتنبيه متكرر. */
    static void scheduleNext(Context c, JSONObject a) {
        scheduleNext(c, a, System.currentTimeMillis());
    }

    /** يجدول أول موعد بعد اللحظة after (عند الرنين نمرّر الآن + دقيقة حتى لا يتكرر في نفس الدقيقة). */
    static void scheduleNext(Context c, JSONObject a, long after) {
        long at = nextTime(a.optInt("hour"), a.optInt("minute"), a.optInt("weekday", 0), after);
        setAlarm(c, a, at);
    }

    static void setAlarm(Context c, JSONObject a, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pendingIntent(c, a);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static long nextTime(int hour, int minute, int weekday, long now) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 8; i++) {
            boolean dayOk = weekday == 0 || cal.get(Calendar.DAY_OF_WEEK) == weekday;
            if (dayOk && cal.getTimeInMillis() > now) break;
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return cal.getTimeInMillis();
    }

    private static PendingIntent pendingIntent(Context c, JSONObject a) {
        Intent intent = new Intent(c, AlarmReceiver.class)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ALARM, a.toString());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, a.optInt("id"), intent, flags);
    }

    private static JSONArray load(Context c) {
        try {
            return new JSONArray(prefs(c).getString(KEY, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
