package com.dezgre.mobile;

import org.json.JSONObject;

import java.util.Locale;

final class MobileNotificationEvent {
    static final String WEB_ORDER_CREATED = "WEB_ORDER_CREATED";
    static final String AI_ORDER_CREATED = "AI_ORDER_CREATED";
    static final String WHATSAPP_CONNECTION_DISCONNECTED = "WHATSAPP_CONNECTION_DISCONNECTED";
    static final String WHATSAPP_RECONNECT_STARTED = "WHATSAPP_RECONNECT_STARTED";

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
        return WEB_ORDER_CREATED.equals(eventType)
                || AI_ORDER_CREATED.equals(eventType)
                || WHATSAPP_CONNECTION_DISCONNECTED.equals(eventType)
                || WHATSAPP_RECONNECT_STARTED.equals(eventType);
    }

    boolean isOrderEvent() {
        return WEB_ORDER_CREATED.equals(eventType) || AI_ORDER_CREATED.equals(eventType);
    }

    String routeTab() {
        return isOrderEvent() ? "orders" : "connections";
    }

    String resolvedTitle() {
        if (!title.isEmpty()) return title;
        if (WEB_ORDER_CREATED.equals(eventType)) return "Nueva venta web";
        if (AI_ORDER_CREATED.equals(eventType)) return "Nueva venta de NATI";
        if (WHATSAPP_CONNECTION_DISCONNECTED.equals(eventType)) return "WhatsApp desconectado";
        if (WHATSAPP_RECONNECT_STARTED.equals(eventType)) return "Reconexión de WhatsApp";
        return "DEZGRE";
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
