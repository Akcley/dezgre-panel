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
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

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

    private static final String CATEGORY_GENERAL = "general";
    private static final String CATEGORY_SUPPORT = "support";
    private static final String CATEGORY_SALE = "sale";
    private static final String CATEGORY_REGISTERED = "registered";

    // Versioned on purpose: Android freezes a channel's sound after creation.
    private static final String CHANNEL_GENERAL = "nativa_general_v2";
    private static final String CHANNEL_SUPPORT = "nativa_support_v2";
    private static final String CHANNEL_SALE = "nativa_sale_v2";
    private static final String CHANNEL_REGISTERED = "nativa_registered_order_v2";

    private DezgreNotificationManager() {}

    static boolean hasPermission(Context context) {
        ensureOfficialChannels(context);
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
        ensureOfficialChannels(activity);
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
        if (!hasPermission(context)) return false;

        NotificationConfig config = new NotificationConfigStore(context).get(event.storeId, event.eventType);
        if (!config.enabled) return false;

        // One central eventId remains the single deduplication key.
        if (!new NotificationEventDeduplicator(context).markIfNew(event.eventId)) return false;
        return showEvent(context, event);
    }

    private static boolean showEvent(Context context, MobileNotificationEvent event) {
        return showEvent(
                context,
                event.storeId,
                event.eventType,
                event.resolvedTitle(),
                event.resolvedBody(),
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

        String category = categoryFor(eventType == null || eventType.isEmpty() ? eventKey : eventType);
        String channelId = channelIdFor(category);
        ensureOfficialChannels(context);

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

        builder.setSmallIcon(R.drawable.ic_notification_dezgre)
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
            builder.setSound(officialSoundUri(context, category));
            builder.setVibrate(DEFAULT_VIBRATION);
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
        return showEvent(
                activity,
                storeId,
                MobileNotificationEvent.NOTIFICATION_TEST,
                "Notificación de prueba",
                "Las notificaciones de DEZGRE están funcionando correctamente.",
                "",
                "local-test-" + System.currentTimeMillis()
        );
    }

    private static void ensureOfficialChannels(Context context) {
        if (Build.VERSION.SDK_INT < 26 || context == null) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        // Old dynamically-generated channels keep their previous sound forever on Android.
        // Do not reuse them; remove only this app's legacy "dezgre_*" channels.
        for (NotificationChannel channel : manager.getNotificationChannels()) {
            String id = channel.getId();
            if (id != null && id.startsWith("dezgre_")) {
                manager.deleteNotificationChannel(id);
            }
        }

        ensureOfficialChannel(context, manager, CATEGORY_GENERAL);
        ensureOfficialChannel(context, manager, CATEGORY_SUPPORT);
        ensureOfficialChannel(context, manager, CATEGORY_SALE);
        ensureOfficialChannel(context, manager, CATEGORY_REGISTERED);
    }

    private static void ensureOfficialChannel(
            Context context,
            NotificationManager manager,
            String category
    ) {
        String channelId = channelIdFor(category);
        NotificationChannel existing = manager.getNotificationChannel(channelId);
        if (existing != null) {
            existing.setName(channelName(category));
            existing.setDescription(channelDescription(category));
            manager.createNotificationChannel(existing);
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                channelId,
                channelName(category),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(channelDescription(category));
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
        channel.setSound(officialSoundUri(context, category), attributes);
        manager.createNotificationChannel(channel);
    }

    private static String categoryFor(String eventKey) {
        String key = eventKey == null ? "" : eventKey.trim().toUpperCase(Locale.ROOT);

        if (MobileNotificationEvent.MANUAL_ORDER_CREATED.equals(key)
                || key.contains("REGISTERED_ORDER")
                || key.contains("ORDER_REGISTERED")) {
            return CATEGORY_REGISTERED;
        }

        if (MobileNotificationEvent.WEB_ORDER_CREATED.equals(key)
                || MobileNotificationEvent.AI_ORDER_CREATED.equals(key)
                || key.contains("NEW_SALE")
                || key.contains("SALE_CREATED")) {
            return CATEGORY_SALE;
        }

        if (key.contains("SUPPORT") || key.contains("WEB_CHAT") || key.contains("CHAT_WEB")) {
            return CATEGORY_SUPPORT;
        }

        return CATEGORY_GENERAL;
    }

    private static String channelIdFor(String category) {
        if (CATEGORY_SUPPORT.equals(category)) return CHANNEL_SUPPORT;
        if (CATEGORY_SALE.equals(category)) return CHANNEL_SALE;
        if (CATEGORY_REGISTERED.equals(category)) return CHANNEL_REGISTERED;
        return CHANNEL_GENERAL;
    }

    private static Uri officialSoundUri(Context context, String category) {
        return Uri.parse("android.resource://" + context.getPackageName() + "/" + rawSoundResource(category));
    }

    private static int rawSoundResource(String category) {
        if (CATEGORY_SUPPORT.equals(category)) return R.raw.sonido_soporte;
        if (CATEGORY_SALE.equals(category)) return R.raw.sonido_nueva_venta;
        if (CATEGORY_REGISTERED.equals(category)) return R.raw.sonido_pedido_registrado;
        return R.raw.sonido_general;
    }

    private static String channelName(String category) {
        if (CATEGORY_SUPPORT.equals(category)) return "Soporte / Chat Web";
        if (CATEGORY_SALE.equals(category)) return "Nueva venta";
        if (CATEGORY_REGISTERED.equals(category)) return "Pedidos registrados";
        return "General";
    }

    private static String channelDescription(String category) {
        if (CATEGORY_SUPPORT.equals(category)) return "Mensajes y eventos de Soporte o Chat Web.";
        if (CATEGORY_SALE.equals(category)) return "Nuevos pedidos y ventas de tu operación.";
        if (CATEGORY_REGISTERED.equals(category)) return "Pedidos creados manualmente desde Registro de Pedidos.";
        return "Avisos generales del sistema y WhatsApp.";
    }

    private static boolean isConnectionEvent(String eventType) {
        return MobileNotificationEvent.WHATSAPP_CONNECTION_DISCONNECTED.equals(eventType)
                || MobileNotificationEvent.WHATSAPP_RECONNECT_STARTED.equals(eventType);
    }
}
