package com.dezgre.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

final class NotificationEventDeduplicator {
    private static final Object LOCK = new Object();
    private static final String PREFS = "dezgre_notification_events_v1";
    private static final String KEY = "seen";
    private static final int MAX_EVENTS = 256;
    private static final long TTL_MS = 7L * 24L * 60L * 60L * 1000L;

    private final SharedPreferences prefs;

    NotificationEventDeduplicator(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean markIfNew(String eventId) {
        String cleanId = eventId == null ? "" : eventId.trim();
        if (cleanId.isEmpty()) return false;
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            JSONObject seen = readSeen();
            pruneExpired(seen, now);
            if (seen.has(cleanId)) {
                prefs.edit().putString(KEY, seen.toString()).apply();
                return false;
            }
            try {
                seen.put(cleanId, now);
            } catch (Exception ignored) {
                return false;
            }
            trimOldest(seen);
            return prefs.edit().putString(KEY, seen.toString()).commit();
        }
    }

    private JSONObject readSeen() {
        String raw = prefs.getString(KEY, "{}");
        try {
            return new JSONObject(raw == null || raw.isEmpty() ? "{}" : raw);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private void pruneExpired(JSONObject seen, long now) {
        List<String> expired = new ArrayList<>();
        Iterator<String> keys = seen.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            long timestamp = seen.optLong(key, 0L);
            if (timestamp <= 0L || now - timestamp > TTL_MS) expired.add(key);
        }
        for (String key : expired) seen.remove(key);
    }

    private void trimOldest(JSONObject seen) {
        while (seen.length() > MAX_EVENTS) {
            String oldestKey = null;
            long oldest = Long.MAX_VALUE;
            Iterator<String> keys = seen.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                long timestamp = seen.optLong(key, 0L);
                if (timestamp < oldest) {
                    oldest = timestamp;
                    oldestKey = key;
                }
            }
            if (oldestKey == null) break;
            seen.remove(oldestKey);
        }
    }
}
