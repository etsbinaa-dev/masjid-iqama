package org.masjid.iqama;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONObject;

/**
 * يستقبل موعد التنبيه: يعرض إشعاراً صامتاً، ويشغّل "حي على الصلاة" بنفسه على صوت المنبّه
 * (مثل منبّه الساعة) فيُسمع حتى في الوضع الصامت، ثم يجدول الموعد القادم.
 */
public class AlarmReceiver extends BroadcastReceiver {

    static final String ACTION_STOP = "org.masjid.iqama.IQAMA_STOP";
    private static final String CHANNEL_ID = "iqama_silent";
    private static final long MAX_PLAY_MS = 15000;

    private static MediaPlayer player;
    private static PowerManager.WakeLock wakeLock;
    private static PendingResult pending;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final java.util.Map<Integer, Long> lastFired = new java.util.HashMap<>();

    @Override
    public void onReceive(Context context, Intent intent) {
        Context c = context.getApplicationContext();
        if (ACTION_STOP.equals(intent.getAction())) {
            stopSound();
            return;
        }
        JSONObject a;
        try {
            a = new JSONObject(intent.getStringExtra(IqamaAlarms.EXTRA_ALARM));
        } catch (Exception e) {
            return;
        }
        long now = System.currentTimeMillis();
        // الموعد القادم بعد دقيقة على الأقل، حتى لو وصل التنبيه قبل وقته بلحظة
        if (a.optBoolean("repeat", true)) IqamaAlarms.scheduleNext(c, a, now + 60_000L);
        // فحص الموقع قبل الصلاة بساعة: لا صوت ولا إشعار، فقط اختيار أقرب مسجد
        if (a.optBoolean("check", false)) {
            LocationCheck.run(c, a.optInt("slot"), goAsync());
            return;
        }
        // حماية: لا يرنّ نفس التنبيه مرتين خلال دقيقة
        synchronized (lastFired) {
            Long last = lastFired.get(a.optInt("id"));
            if (last != null && now - last < 60_000L) return;
            lastFired.put(a.optInt("id"), now);
        }
        showNotification(c, a);
        // الصوت المختار في الإعدادات: hayya (حي على الصلاة) أو chime (جرس) أو vibrate (اهتزاز فقط)
        playSound(c, goAsync(), a.optString("sound", "hayya"));
    }

    private static Vibrator vibrator;

    private static synchronized void playSound(Context c, PendingResult result, String sound) {
        stopSound();
        pending = result;
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iqama:sound");
            wakeLock.acquire(MAX_PLAY_MS + 5000);
        }
        if ("vibrate".equals(sound)) {
            vibrate(c);
            handler.postDelayed(AlarmReceiver::stopSound, 4000);
            return;
        }
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
            int res = "chime".equals(sound) ? R.raw.iqama_chime : R.raw.hayya_salah;
            AssetFileDescriptor afd = c.getResources().openRawResourceFd(res);
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            player.setOnCompletionListener(mp -> stopSound());
            player.setOnErrorListener((mp, what, extra) -> {
                stopSound();
                return true;
            });
            player.prepare();
            player.start();
            handler.postDelayed(AlarmReceiver::stopSound, MAX_PLAY_MS);
        } catch (Exception e) {
            stopSound();
        }
    }

    /** اهتزاز فقط، كاهتزاز المنبّه (يعمل في الوضع الصامت). */
    private static void vibrate(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                vibrator = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator == null) return;
            long[] pattern = { 0, 600, 300, 600, 300, 900 };
            AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), attrs);
        } catch (Exception ignored) {}
    }

    /** يوقف الصوت ويحرّر كل شيء. آمن للاستدعاء أكثر من مرة. */
    static synchronized void stopSound() {
        handler.removeCallbacksAndMessages(null);
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Exception ignored) {}
            vibrator = null;
        }
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {}
            player.release();
            player = null;
        }
        if (wakeLock != null) {
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock = null;
        }
        if (pending != null) {
            pending.finish();
            pending = null;
        }
    }

    private static void showNotification(Context c, JSONObject a) {
        if (Build.VERSION.SDK_INT >= 33
            && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "تنبيه الإقامة", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("إشعار الإقامة؛ الصوت يشغّله التطبيق على صوت المنبّه");
                ch.setSound(null, null);
                ch.enableVibration(true);
                nm.createNotificationChannel(ch);
            }
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(c, 0, open, flags);
        Intent stop = new Intent(c, AlarmReceiver.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getBroadcast(c, 1000, stop, flags);

        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_iqama)
            .setColor(0xFFD4A84B)
            .setContentTitle(a.optString("title"))
            .setContentText(a.optString("body"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .setDeleteIntent(stopPi)
            .addAction(0, "إيقاف الصوت", stopPi);
        try {
            NotificationManagerCompat.from(c).notify(a.optInt("id"), b.build());
        } catch (SecurityException ignored) {}
    }
}
