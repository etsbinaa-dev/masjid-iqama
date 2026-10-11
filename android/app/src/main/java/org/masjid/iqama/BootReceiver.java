package org.masjid.iqama;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** يعيد جدولة تنبيهات الإقامة بعد إعادة تشغيل الجوال أو تحديث التطبيق أو تغيير الوقت. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        IqamaAlarms.scheduleAll(context.getApplicationContext());
    }
}
