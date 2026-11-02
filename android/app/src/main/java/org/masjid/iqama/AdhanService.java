package org.masjid.iqama;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import java.io.File;

/**
 * يشغّل صوتاً طويلاً (مثل الأذان كاملاً) على صوت المنبّه، في خدمة أمامية حتى لا يوقفه النظام.
 * يتوقف عند انتهاء الصوت، أو بزر «إيقاف» في الإشعار، أو بالضغط على زر الصوت في جانب الجوال.
 * ملفات الأصوات تنسخها الواجهة إلى مجلد sounds داخل التطبيق، فتصل الأصوات الجديدة بالتحديث التلقائي.
 */
public class AdhanService extends Service {

    static final String EXTRA_FILE = "file";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_BODY = "body";
    static final String ACTION_STOP = "org.masjid.iqama.ADHAN_STOP";
    private static final String CHANNEL_ID = "iqama_silent";
    private static final int NOTIF_ID = 777;
    // الأذان كاملاً بضع دقائق؛ حدّ أعلى احتياطاً
    private static final long MAX_PLAY_MS = 6 * 60 * 1000L;
    // أحداث الصوت في أول لحظة قد يرسلها النظام نفسه عند بدء التشغيل، فلا نعدّها ضغطاً
    private static final long IGNORE_VOLUME_MS = 1500;

    private static AdhanService instance;
    private MediaPlayer player;
    private PowerManager.WakeLock wakeLock;
    private long startedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (SystemClock.elapsedRealtime() - startedAt > IGNORE_VOLUME_MS) finish();
        }
    };

    static File soundFile(Context c, String name) {
        return new File(new File(c.getFilesDir(), "sounds"), name);
    }

    /** يبدأ تشغيل الملف؛ يرجع false إن لم يوجد الملف أو رفض النظام بدء الخدمة. */
    static boolean start(Context c, String file, String title, String body) {
        if (!soundFile(c, file).exists()) return false;
        try {
            Intent i = new Intent(c, AdhanService.class)
                .putExtra(EXTRA_FILE, file).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_BODY, body);
            ContextCompat.startForegroundService(c, i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** يوقف الأذان إن كان يعمل. آمن للاستدعاء دائماً. */
    static void stopNow() {
        AdhanService s = instance;
        if (s != null) s.handler.post(s::finish);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            finish();
            return START_NOT_STICKY;
        }
        instance = this;
        String title = intent.getStringExtra(EXTRA_TITLE);
        String body = intent.getStringExtra(EXTRA_BODY);
        try {
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : 0;
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(title, body), type);
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        play(intent.getStringExtra(EXTRA_FILE));
        return START_NOT_STICKY;
    }

    private void play(String file) {
        releasePlayer();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iqama:adhan");
            wakeLock.acquire(MAX_PLAY_MS + 10000);
        }
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
            player.setDataSource(soundFile(this, file).getAbsolutePath());
            player.setOnCompletionListener(mp -> finish());
            player.setOnErrorListener((mp, what, extra) -> {
                finish();
                return true;
            });
            player.prepare();
            player.start();
            startedAt = SystemClock.elapsedRealtime();
            ContextCompat.registerReceiver(this, volumeReceiver,
                new IntentFilter("android.media.VOLUME_CHANGED_ACTION"), ContextCompat.RECEIVER_EXPORTED);
            handler.postDelayed(this::finish, MAX_PLAY_MS);
        } catch (Exception e) {
            finish();
        }
    }

    private Notification buildNotification(String title, String body) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "تنبيه الإقامة", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("إشعار الإقامة؛ الصوت يشغّله التطبيق على صوت المنبّه");
                ch.setSound(null, null);
                ch.enableVibration(true);
                nm.createNotificationChannel(ch);
            }
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, flags);
        Intent stop = new Intent(this, AdhanService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1001, stop, flags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_iqama)
            .setColor(0xFFD4A84B)
            .setContentTitle(title != null ? title : "الأذان")
            .setContentText(body != null ? body : "")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .setOngoing(true)
            .setContentIntent(openPi)
            .setDeleteIntent(stopPi)
            .addAction(0, "إيقاف الأذان", stopPi)
            .build();
    }

    private void releasePlayer() {
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(volumeReceiver);
        } catch (Exception ignored) {}
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {}
            player.release();
            player = null;
        }
    }

    private void finish() {
        releasePlayer();
        if (wakeLock != null) {
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock = null;
        }
        if (instance == this) instance = null;
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        releasePlayer();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
