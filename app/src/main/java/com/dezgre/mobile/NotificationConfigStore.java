package com.dezgre.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

final class NotificationConfigStore {
    private static final String PREFS = "dezgre_notification_config_v1";
    private static final String PREFIX = "config.";
    private final SharedPreferences prefs;

    NotificationConfigStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    NotificationConfig get(String storeId, String eventKey) {
        String raw = prefs.getString(key(storeId, eventKey), null);
        if (raw == null || raw.isEmpty()) return NotificationConfig.defaultFor(eventKey);
        try {
            return NotificationConfig.fromJson(new JSONObject(raw));
        } catch (Exception ignored) {
            return NotificationConfig.defaultFor(eventKey);
        }
    }

    void put(String storeId, NotificationConfig config) {
        prefs.edit().putString(key(storeId, config.eventKey), config.toJson().toString()).apply();
    }

    int applyRemote(String storeId, JSONArray configs) {
        if (configs == null) return 0;
        SharedPreferences.Editor editor = prefs.edit();
        int saved = 0;
        for (int i = 0; i < configs.length(); i++) {
            JSONObject item = configs.optJSONObject(i);
            if (item == null) continue;
            NotificationConfig config = NotificationConfig.fromJson(item);
            if ("unknown".equals(config.eventKey)) continue;
            editor.putString(key(storeId, config.eventKey), config.toJson().toString());
            saved++;
        }
        editor.apply();
        return saved;
    }

    void clearStore(String storeId) {
        String prefix = PREFIX + safe(storeId) + ".";
        SharedPreferences.Editor editor = prefs.edit();
        for (String candidate : prefs.getAll().keySet()) {
            if (candidate.startsWith(prefix)) editor.remove(candidate);
        }
        editor.apply();
    }

    private String key(String storeId, String eventKey) {
        return PREFIX + safe(storeId) + "." + safe(eventKey);
    }

    static String safe(String value) {
        if (value == null || value.trim().isEmpty()) return "current";
        return value.trim().toLowerCase().replaceAll("[^a-z0-9._-]", "_");
    }
}
