package com.dezgre.mobile;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.telephony.SmsManager;

public final class SmsRespondViaMessageService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Uri data = intent == null ? null : intent.getData();
            String destination = data == null ? "" : data.getSchemeSpecificPart();
            CharSequence text = intent == null ? null : intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (destination != null && !destination.trim().isEmpty()
                    && text != null && text.length() > 0
                    && SmsGatewayRuntime.hasSendPermission(this)) {
                SmsManager.getDefault().sendTextMessage(destination.trim(), null, text.toString(), null, null);
            }
        } catch (Exception ignored) {
        } finally {
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
