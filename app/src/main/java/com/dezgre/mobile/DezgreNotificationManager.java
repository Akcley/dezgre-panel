package com.dezgre.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

final class DezgreNotificationManager {
    static final int REQUEST_NOTIFICATIONS = 4107;
    static final String EXTRA_DEEP_LINK = "dezgre_deep_link";
    private static final String GROUP_ID = "dezgre_events";

    private DezgreNotificationManager() {}

    static boolean hasPermission(Context context) {
        return Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    static void requestPermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(activity)) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    static String permissionLabel(Context context) {
        if (Build.VERSION.SDK_INT < 33) return "Activo";
        return hasPermission(context) ? "Activo" : "Pendiente";
    }

    static int applyRemoteConfig(Context context, String storeId, JSONArray configs) {
        return new NotificationConfigStore(context).applyRemote(storeId, configs);
    }

    static boolean showPayload(Context context, JSONObject payload) {
        if (payload == null) return false;
        String storeId = first(payload, "store_id", "storeId");
        String eventKey = first(payload, "event_key", "eventKey");
        if (storeId.isEmpty() || eventKey.isEmpty()) return false;
        String title = first(payload, "title");
        String body = first(payload, "body", "message");
        String deepLink = first(payload, "deep_link", "deepLink");
        String notificationId = first(payload, "notification_id", "notificationId");
        return showEvent(context, storeId, eventKey,
                title.isEmpty() ? "DEZGRE" : title,
                body,
                deepLink,
                notificationId);
    }

    static boolean showEvent(
            Context context,
            String storeId,
            String eventKey,
            String title,
            String body,
            String deepLink,
            String notificationId
    ) {
        if (!hasPermission(context)) return false;
        NotificationConfig config = new NotificationConfigStore(context).get(storeId, eventKey);
        if (!config.enabled) return false;

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        String channelId = channelId(storeId, config);
        ensureChannel(manager, channelId, config);

        Intent open = new Intent(context, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (deepLink != null && !deepLink.isEmpty()) open.putExtra(EXTRA_DEEP_LINK, deepLink);
        int requestCode = Math.abs((storeId + ":" + eventKey + ":" + notificationId).hashCode());
        PendingIntent pending = PendingIntent.getActivity(
                context,
                requestCode,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, channelId)
                : new Notification.Builder(context);
        builder.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title == null || title.isEmpty() ? "DEZGRE" : title)
                .setContentText(body == null ? "" : body)
                .setStyle(new Notification.BigTextStyle().bigText(body == null ? "" : body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setGroup(GROUP_ID)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PRIVATE);

        if (Build.VERSION.SDK_INT < 26) {
            if ("silent".equalsIgnoreCase(config.soundMode)) {
                builder.setSound(null);
            } else if ("custom".equalsIgnoreCase(config.soundMode) && !config.localSoundUri.isEmpty()) {
                builder.setSound(Uri.parse(config.localSoundUri));
            } else {
                builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
            }
        }

        int id = notificationId == null || notificationId.isEmpty()
                ? (int) (System.currentTimeMillis() & 0x7fffffff)
                : Math.abs(notificationId.hashCode());
        manager.notify(id, builder.build());
        return true;
    }

    static boolean showTest(Activity activity, String storeId) {
        if (!hasPermission(activity)) {
            requestPermission(activity);
            return false;
        }
        String testEvent = "mobile_test";
        NotificationConfigStore store = new NotificationConfigStore(activity);
        store.put(storeId, new NotificationConfig(testEvent, true, "default", "Sistema", "", "", "test-v1"));
        return showEvent(
                activity,
                storeId,
                testEvent,
                "DEZGRE · Prueba",
                "El motor de notificaciones Android está funcionando.",
                "",
                "local-test-" + System.currentTimeMillis()
        );
    }

    private static void ensureChannel(NotificationManager manager, String channelId, NotificationConfig config) {
        if (Build.VERSION.SDK_INT < 26) return;
        if (manager.getNotificationChannel(channelId) != null) return;

        NotificationChannel channel = new NotificationChannel(
                channelId,
                channelName(config.eventKey),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("DEZGRE · " + config.eventKey + " · " + config.soundRevision);
        channel.enableVibration(true);

        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        if ("silent".equalsIgnoreCase(config.soundMode)) {
            channel.setSound(null, null);
        } else if ("custom".equalsIgnoreCase(config.soundMode) && !config.localSoundUri.isEmpty()) {
            channel.setSound(Uri.parse(config.localSoundUri), attributes);
        } else {
            channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), attributes);
        }
        manager.createNotificationChannel(channel);
    }

    private static String channelId(String storeId, NotificationConfig config) {
        String signature = NotificationConfigStore.safe(storeId) + "|" + config.eventKey + "|"
                + config.soundMode + "|" + config.soundRevision + "|" + config.localSoundUri;
        return "dezgre_" + NotificationConfigStore.safe(config.eventKey) + "_" + Integer.toHexString(signature.hashCode());
    }

    private static String channelName(String eventKey) {
        if ("whatsapp_message".equals(eventKey)) return "Nuevo mensaje de WhatsApp";
        if ("support_chat".equals(eventKey)) return "Soporte / Chat Web";
        if ("mobile_test".equals(eventKey)) return "Prueba de notificaciones";
        return "DEZGRE · " + eventKey.replace('_', ' ');
    }

    private static String first(JSONObject json, String... keys) {
        for (String key : keys) {
            String value = json.optString(key, "");
            if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) return value;
        }
        return "";
    }
}
