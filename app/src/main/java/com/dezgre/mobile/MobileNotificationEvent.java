package com.dezgre.mobile;

import org.json.JSONObject;

import java.util.Locale;

final class MobileNotificationEvent {
    static final String WEB_ORDER_CREATED = "WEB_ORDER_CREATED";
    static final String AI_ORDER_CREATED = "AI_ORDER_CREATED";
    static final String MANUAL_ORDER_CREATED = "MANUAL_ORDER_CREATED";
    static final String WHATSAPP_CONNECTION_DISCONNECTED = "WHATSAPP_CONNECTION_DISCONNECTED";
    static final String WHATSAPP_RECONNECT_STARTED = "WHATSAPP_RECONNECT_STARTED";
    static final String NOTIFICATION_TEST = "NOTIFICATION_TEST";

    final String eventId;
    final String eventType;
    final String storeId;
    final String title;
    final String body;
    final String resourceId;
    final String occurredAt;

    private MobileNotificationEvent(
            String eventId,
            String eventType,
            String storeId,
            String title,
            String body,
            String resourceId,
            String occurredAt
    ) {
        this.eventId = clean(eventId);
        this.eventType = clean(eventType).toUpperCase(Locale.ROOT);
        this.storeId = clean(storeId);
        this.title = clean(title);
        this.body = clean(body);
        this.resourceId = clean(resourceId);
        this.occurredAt = clean(occurredAt);
    }

    static MobileNotificationEvent fromJson(JSONObject json) {
        if (json == null) return null;
        String eventId = first(json, "eventId", "event_id");
        String eventType = first(json, "eventType", "event_type", "eventKey", "event_key").toUpperCase(Locale.ROOT);
        String storeId = first(json, "storeId", "store_id");
        if (eventId.isEmpty() || storeId.isEmpty() || !isSupported(eventType)) return null;

        String resourceId = first(
                json,
                "resourceId", "resource_id",
                "orderId", "order_id",
                "connectionId", "connection_id"
        );
        return new MobileNotificationEvent(
                eventId,
                eventType,
                storeId,
                first(json, "title"),
                first(json, "body", "message"),
                resourceId,
                first(json, "occurredAt", "occurred_at")
        );
    }

    static boolean isSupported(String eventType) {
        // The central notification bus is authoritative. Keeping this parser forward-compatible
        // lets new general/support events reuse the same FCM path without adding a second push system.
        return eventType != null && !eventType.trim().isEmpty();
    }

    boolean isOrderEvent() {
        return WEB_ORDER_CREATED.equals(eventType)
                || AI_ORDER_CREATED.equals(eventType)
                || MANUAL_ORDER_CREATED.equals(eventType);
    }

    String routeTab() {
        if (NOTIFICATION_TEST.equals(eventType)) return "home";
        if (isOrderEvent()) return "orders";
        if (WHATSAPP_CONNECTION_DISCONNECTED.equals(eventType)
                || WHATSAPP_RECONNECT_STARTED.equals(eventType)) {
            return "connections";
        }
        return "home";
    }

    String resolvedTitle() {
        if (NOTIFICATION_TEST.equals(eventType)) return "Notificación de prueba";
        if (!title.isEmpty()) return title;
        if (WEB_ORDER_CREATED.equals(eventType)) return "Nueva venta web";
        if (AI_ORDER_CREATED.equals(eventType)) return "Nueva venta de NATI";
        if (MANUAL_ORDER_CREATED.equals(eventType)) return "Pedido registrado";
        if (WHATSAPP_CONNECTION_DISCONNECTED.equals(eventType)) return "WhatsApp desconectado";
        if (WHATSAPP_RECONNECT_STARTED.equals(eventType)) return "Reconexión de WhatsApp";
        return "DEZGRE";
    }

    String resolvedBody() {
        if (NOTIFICATION_TEST.equals(eventType)) {
            return "Las notificaciones de DEZGRE están funcionando correctamente.";
        }
        return body;
    }

    private static String first(JSONObject json, String... keys) {
        for (String key : keys) {
            String value = clean(json.optString(key, ""));
            if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) return value;
        }
        return "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
