package com.dezgre.mobile;

import android.Manifest;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SmsManager;

import org.json.JSONObject;
import org.json.JSONArray;
import java.util.concurrent.atomic.AtomicBoolean;

import java.util.ArrayList;

final class SmsGatewayRuntime {
    static final String ACTION_SENT = "com.dezgre.mobile.SMS_GATEWAY_SENT";
    static final String ACTION_DELIVERED = "com.dezgre.mobile.SMS_GATEWAY_DELIVERED";
    static final String EXTRA_COMMAND_ID = "sms_command_id";
    static final String EXTRA_DESTINATION = "sms_destination";
    static final String EXTRA_BODY = "sms_body";

    private static final AtomicBoolean heartbeatRunning = new AtomicBoolean(false);
    private SmsGatewayRuntime() {}

    static boolean isDefaultSmsApp(Context context) {
        String current = Telephony.Sms.getDefaultSmsPackage(context);
        return current != null && current.equals(context.getPackageName());
    }

    static boolean hasSendPermission(Context context) {
        return Build.VERSION.SDK_INT < 23
                || context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasReceivePermission(Context context) {
        return Build.VERSION.SDK_INT < 23
                || context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    static void heartbeat(Context context, String pushToken) {
        SecureSmsGatewayStore store = new SecureSmsGatewayStore(context);
        String token = store.token();
        if (token.isEmpty()) return;

        if (!heartbeatRunning.compareAndSet(false, true)) return;
        JSONObject body = new JSONObject();
        try {
            body.put("pushToken", pushToken == null ? "" : pushToken.trim());
            body.put("smsPermissionGranted", hasSendPermission(context));
            body.put("receivePermissionGranted", hasReceivePermission(context));
            body.put("defaultSms", isDefaultSmsApp(context));
        } catch (Exception ignored) {
            heartbeatRunning.set(false);
            return;
        }

        ApiClient api = new ApiClient();
        api.post("/sms/gateway/heartbeat", token, body, new ApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject json) {
                try {
                    JSONArray commands = json.optJSONArray("commandIds");
                    if (commands != null) for (int i=0;i<commands.length();i++) {
                        handleCommand(context.getApplicationContext(), commands.optString(i, ""));
                    }
                } finally { heartbeatRunning.set(false); api.shutdown(); }
            }

            @Override
            public void onError(ApiClient.ApiException error) {
                if (error != null && error.status == 401) store.clear();
                heartbeatRunning.set(false);
                api.shutdown();
            }
        });
    }

    static void handleCommand(Context context, String commandId) {
        String cleanId = commandId == null ? "" : commandId.trim();
        if (cleanId.isEmpty()) return;
        SecureSmsGatewayStore store = new SecureSmsGatewayStore(context);
        String token = store.token();
        if (token.isEmpty()) return;

        ApiClient api = new ApiClient();
        api.post("/sms/gateway/commands/" + cleanId, token, new JSONObject(), new ApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject json) {
                JSONObject command = json.optJSONObject("command");
                if (command == null) { api.shutdown(); return; }
                String destination = command.optString("destination", "").trim();
                String body = command.optString("body", "");
                if (destination.isEmpty() || body.isEmpty()) {
                    reportStatus(context, cleanId, "FAILED", "Orden SMS incompleta");
                    api.shutdown();
                    return;
                }
                sendSms(context, cleanId, destination, body);
                api.shutdown();
            }

            @Override
            public void onError(ApiClient.ApiException error) {
                api.shutdown();
            }
        });
    }

    static void sendSms(Context context, String commandId, String destination, String body) {
        if (!hasSendPermission(context)) {
            reportStatus(context, commandId, "FAILED", "Permiso SMS no disponible");
            return;
        }
        try {
            SmsManager manager = SmsManager.getDefault();
            ArrayList<String> parts = manager.divideMessage(body);
            if (parts == null || parts.isEmpty()) {
                parts = new ArrayList<>();
                parts.add(body);
            }
            SmsGatewayPartTracker.prepare(context, commandId, parts.size());
            ArrayList<PendingIntent> sentIntents = new ArrayList<>();
            ArrayList<PendingIntent> deliveredIntents = new ArrayList<>();

            for (int i = 0; i < parts.size(); i++) {
                int base = Math.abs((commandId + ":" + i).hashCode());

                Intent sent = new Intent(context, SmsSentReceiver.class)
                        .setAction(ACTION_SENT)
                        .putExtra(EXTRA_COMMAND_ID, commandId)
                        .putExtra(EXTRA_DESTINATION, destination)
                        .putExtra(EXTRA_BODY, body);
                sentIntents.add(PendingIntent.getBroadcast(
                        context,
                        base,
                        sent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                ));

                Intent delivered = new Intent(context, SmsDeliveredReceiver.class)
                        .setAction(ACTION_DELIVERED)
                        .putExtra(EXTRA_COMMAND_ID, commandId);
                deliveredIntents.add(PendingIntent.getBroadcast(
                        context,
                        base + 100000,
                        delivered,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                ));
            }

            manager.sendMultipartTextMessage(destination, null, parts, sentIntents, deliveredIntents);
        } catch (Exception error) {
            reportStatus(context, commandId, "FAILED", "Android no pudo enviar el SMS");
        }
    }

    static void reportStatus(Context context, String commandId, String status, String error) {
        SecureSmsGatewayStore store = new SecureSmsGatewayStore(context);
        String token = store.token();
        if (token.isEmpty()) return;
        JSONObject body = new JSONObject();
        try {
            body.put("commandId", commandId);
            body.put("status", status);
            body.put("error", error == null ? "" : error);
        } catch (Exception ignored) {
            return;
        }
        ApiClient api = new ApiClient();
        api.post("/sms/gateway/status", token, body, new ApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject json) { api.shutdown(); }

            @Override
            public void onError(ApiClient.ApiException apiError) { api.shutdown(); }
        });
    }

    static void reportInbound(Context context, String messageId, String phone, String bodyText, long timestampMs) {
        SecureSmsGatewayStore store = new SecureSmsGatewayStore(context);
        String token = store.token();
        if (token.isEmpty()) return;

        JSONObject body = new JSONObject();
        try {
            body.put("messageId", messageId);
            body.put("phone", phone);
            body.put("body", bodyText);
            body.put("occurredAt", timestampMs);
        } catch (Exception ignored) {
            return;
        }
        ApiClient api = new ApiClient();
        api.post("/sms/gateway/inbound", token, body, new ApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject json) { api.shutdown(); }

            @Override
            public void onError(ApiClient.ApiException error) { api.shutdown(); }
        });
    }

    static void insertSentIntoProvider(Context context, String destination, String body) {
        if (!isDefaultSmsApp(context)) return;
        try {
            ContentValues values = new ContentValues();
            values.put(Telephony.Sms.ADDRESS, destination);
            values.put(Telephony.Sms.BODY, body);
            values.put(Telephony.Sms.DATE, System.currentTimeMillis());
            values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT);
            context.getContentResolver().insert(Telephony.Sms.Sent.CONTENT_URI, values);
        } catch (Exception ignored) {}
    }

    static void insertInboundIntoProvider(Context context, String address, String body, long timestampMs) {
        if (!isDefaultSmsApp(context)) return;
        try {
            ContentValues values = new ContentValues();
            values.put(Telephony.Sms.ADDRESS, address);
            values.put(Telephony.Sms.BODY, body);
            values.put(Telephony.Sms.DATE, timestampMs);
            values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX);
            values.put(Telephony.Sms.READ, 0);
            context.getContentResolver().insert(Telephony.Sms.Inbox.CONTENT_URI, values);
        } catch (Exception ignored) {}
    }
}

