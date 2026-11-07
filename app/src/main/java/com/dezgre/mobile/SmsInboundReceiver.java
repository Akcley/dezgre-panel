package com.dezgre.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class SmsInboundReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(action)
                && !Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(action)) {
            return;
        }

        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages == null || messages.length == 0) return;

        String address = "";
        StringBuilder body = new StringBuilder();
        long timestamp = System.currentTimeMillis();
        for (SmsMessage message : messages) {
            if (message == null) continue;
            if (address.isEmpty() && message.getOriginatingAddress() != null) {
                address = message.getOriginatingAddress();
            }
            if (message.getMessageBody() != null) body.append(message.getMessageBody());
            if (message.getTimestampMillis() > 0) timestamp = message.getTimestampMillis();
        }
        if (address == null || address.trim().isEmpty() || body.length() == 0) return;

        String text = body.toString();
        String messageId = fingerprint(address, text, timestamp);
        if (Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(action)) {
            SmsGatewayRuntime.insertInboundIntoProvider(context, address, text, timestamp);
        }
        SmsGatewayRuntime.reportInbound(context, messageId, address, text, timestamp);
    }

    private static String fingerprint(String address, String body, long timestamp) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((address + "|" + timestamp + "|" + body).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("sms-");
            for (int i = 0; i < Math.min(16, digest.length); i++) {
                hex.append(String.format(java.util.Locale.US, "%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception ignored) {
            return "sms-" + Math.abs((address + timestamp + body).hashCode());
        }
    }
}
