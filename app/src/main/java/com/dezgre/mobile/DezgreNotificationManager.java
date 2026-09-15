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
    static final String EXTRA_ROUTE_TAB = "dezgre_notification_route_tab";
    static final String EXTRA_RESOURCE_ID = "dezgre_notification_resource_id";
    static final String EXTRA_EVENT_ID = "dezgre_notification_event_id";
    static final String EXTRA_EVENT_TYPE = "dezgre_notification_event_type";
    static final String EXTRA_STORE_ID = "dezgre_notification_store_id";
    private static final String EXTRA_DEEP_LINK = "dezgre_deep_link";
    private static final String GROUP_ID = "dezgre_events";
    private static final long[] DEFAULT_VIBRATION = new long[]{0, 180, 90, 180};

    private DezgreNotificationManager() {}

    static boolean hasPermission(Context context) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 24) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            return manager != null && manager.areNotificationsEnabled();
        }
        return true;
    }

    static void requestPermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= 33
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    static String permissionLabel(Context context) {
        return hasPermission(context) ? "Activo" : "Pendiente";
    }

    static int applyRemoteConfig(Context context, String storeId, JSONArray configs) {
        return new NotificationConfigStore(context).applyRemote(storeId, configs);
    }

    static boolean showPayload(Context context, JSONObject payload) {
        MobileNotificationEvent event = MobileNotificationEvent.fromJson(payload);
        if (event == null) return false;
        if (!new NotificationEventDeduplicator(context).markIfNew(event.eventId)) return false;
        return showEvent(context, event);
    }

    private static boolean showEvent(Context context, MobileNotificationEvent event) {
        return showEvent(
                context,
                event.storeId,
                event.eventType,
                event.resolvedTitle(),
                event.body,
                "",
                event.eventId,
                event.routeTab(),
                event.resourceId,
                event.eventType
        );
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
        return showEvent(
                context,
                storeId,
                eventKey,
                title,
                body,
                deepLink,
                notificationId,
                "",
                "",
                eventKey
        );
    }

    private static boolean showEvent(
            Context context,
            String storeId,
            String eventKey,
            String title,
            String body,
            String deepLink,
            String notificationId,
            String routeTab,
            String resourceId,
            String eventType
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
        if (routeTab != null && !routeTab.isEmpty()) open.putExtra(EXTRA_ROUTE_TAB, routeTab);
        if (resourceId != null && !resourceId.isEmpty()) open.putExtra(EXTRA_RESOURCE_ID, resourceId);
        if (notificationId != null && !notificationId.isEmpty()) open.putExtra(EXTRA_EVENT_ID, notificationId);
        if (eventType != null && !eventType.isEmpty()) open.putExtra(EXTRA_EVENT_TYPE, eventType);
        if (storeId != null && !storeId.isEmpty()) open.putExtra(EXTRA_STORE_ID, storeId);

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
                .setCategory(isConnectionEvent(eventType) ? Notification.CATEGORY_STATUS : Notification.CATEGORY_MESSAGE)
                .setPriority(Notification.PRIORITY_HIGH)
                .setVisibility(Notification.VISIBILITY_PRIVATE);

        if (Build.VERSION.SDK_INT < 26) {
            if ("silent".equalsIgnoreCase(config.soundMode)) {
                builder.setSound(null);
            } else if ("custom".equalsIgnoreCase(config.soundMode) && !config.localSoundUri.isEmpty()) {
                builder.setSound(Uri.parse(config.localSoundUri));
                builder.setVibrate(DEFAULT_VIBRATION);
            } else {
                builder.setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE);
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
        resetOldTestChannels(activity);
        String testEvent = "mobile_test";
        NotificationConfigStore store = new NotificationConfigStore(activity);
        store.put(storeId, new NotificationConfig(
                testEvent,
                true,
                "default",
                "Sonido del sistema",
                "",
                "",
                "test-sound-v3"
        ));
        return showEvent(
                activity,
                storeId,
                testEvent,
                "DEZGRE · Prueba",
                "Notificación nativa con sonido y vibración habilitados.",
                "",
                "local-test-" + System.currentTimeMillis()
        );
    }

    private static void resetOldTestChannels(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        for (NotificationChannel channel : manager.getNotificationChannels()) {
            String id = channel.getId();
            if (id != null && id.startsWith("dezgre_mobile_test_")) {
                manager.deleteNotificationChannel(id);
            }
        }
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
        channel.setVibrationPattern(DEFAULT_VIBRATION);
        channel.enableLights(true);
        channel.setLightColor(0xFFB144B2);
        channel.setShowBadge(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);

        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        if ("silent".equalsIgnoreCase(config.soundMode)) {
            channel.setSound(null, null);
        } else if ("custom".equalsIgnoreCase(config.soundMode) && !config.localSoundUri.isEmpty()) {
            channel.setSound(Uri.parse(config.localSoundUri), attributes);
        } else {
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            channel.setSound(sound, attributes);
        }
        manager.createNotificationChannel(channel);
    }

    private static String channelId(String storeId, NotificationConfig config) {
        String signature = NotificationConfigStore.safe(storeId) + "|" + config.eventKey + "|"
                + config.soundMode + "|" + config.soundRevision + "|" + config.localSoundUri;
        return "dezgre_" + NotificationConfigStore.safe(config.eventKey) + "_" + Integer.toHexString(signature.hashCode());
    }

    private static String channelName(String eventKey) {
        if (MobileNotificationEvent.WEB_ORDER_CREATED.equals(eventKey)) return "Ventas web";
        if (MobileNotificationEvent.AI_ORDER_CREATED.equals(eventKey)) return "Ventas de NATI";
        if (MobileNotificationEvent.WHATSAPP_CONNECTION_DISCONNECTED.equals(eventKey)) return "WhatsApp desconectado";
        if (MobileNotificationEvent.WHATSAPP_RECONNECT_STARTED.equals(eventKey)) return "Reconexión de WhatsApp";
        if ("whatsapp_message".equals(eventKey)) return "Nuevo mensaje de WhatsApp";
        if ("support_chat".equals(eventKey)) return "Soporte / Chat Web";
        if ("mobile_test".equals(eventKey)) return "Prueba de notificaciones";
        return "DEZGRE · " + eventKey.replace('_', ' ');
    }

    private static boolean isConnectionEvent(String eventType) {
        return MobileNotificationEvent.WHATSAPP_CONNECTION_DISCONNECTED.equals(eventType)
                || MobileNotificationEvent.WHATSAPP_RECONNECT_STARTED.equals(eventType);
    }
}
