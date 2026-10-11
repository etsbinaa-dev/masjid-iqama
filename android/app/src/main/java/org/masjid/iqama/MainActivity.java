package org.masjid.iqama;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    // قناة تنبيه الإقامة: صوتها على "صوت المنبّه" وليس "صوت الإشعارات"،
    // فيُسمع حتى لو كان الجوال صامتاً، وتبقى إشعارات التطبيقات الأخرى صامتة.
    public static final String ALARM_CHANNEL_ID = "iqama_alarm";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createAlarmChannel();
    }

    private void createAlarmChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(ALARM_CHANNEL_ID) != null) return;

        NotificationChannel channel = new NotificationChannel(
            ALARM_CHANNEL_ID, "تنبيه الإقامة", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("تنبيه قبل إقامة الصلاة، يُسمع حتى في الوضع الصامت");
        channel.enableVibration(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.setBypassDnd(true);

        AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
        Uri sound = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://"
            + getPackageName() + "/" + R.raw.hayya_salah);
        channel.setSound(sound, attributes);

        manager.createNotificationChannel(channel);
    }
}
