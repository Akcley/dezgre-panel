package com.dezgre.mobile;

import org.json.JSONObject;

final class NotificationConfig {
    final String eventKey;
    final boolean enabled;
    final String soundMode;
    final String soundName;
    final String soundUrl;
    final String localSoundUri;
    final String soundRevision;

    NotificationConfig(
            String eventKey,
            boolean enabled,
            String soundMode,
            String soundName,
            String soundUrl,
            String localSoundUri,
            String soundRevision
    ) {
        this.eventKey = clean(eventKey, "unknown");
        this.enabled = enabled;
        this.soundMode = clean(soundMode, "default");
        this.soundName = clean(soundName, "");
        this.soundUrl = clean(soundUrl, "");
        this.localSoundUri = clean(localSoundUri, "");
        this.soundRevision = clean(soundRevision, "v1");
    }

    static NotificationConfig defaultFor(String eventKey) {
        return new NotificationConfig(eventKey, true, "default", "Sistema", "", "", "v1");
    }

    static NotificationConfig fromJson(JSONObject json) {
        if (json == null) return defaultFor("unknown");
        String eventKey = first(json, "event_key", "eventKey", "key");
        boolean enabled = json.has("enabled") ? json.optBoolean("enabled", true) : true;
        String soundMode = first(json, "sound_mode", "soundMode");
        String soundName = first(json, "sound_name", "soundName");
        String soundUrl = first(json, "sound_url", "soundUrl");
        String localSoundUri = first(json, "local_sound_uri", "localSoundUri");
        String revision = first(json, "sound_revision", "soundRevision", "revision");
        if (soundMode.isEmpty()) {
            soundMode = soundUrl.isEmpty() ? "default" : "custom";
        }
        return new NotificationConfig(eventKey, enabled, soundMode, soundName, soundUrl, localSoundUri, revision);
    }

    JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("event_key", eventKey);
            json.put("enabled", enabled);
            json.put("sound_mode", soundMode);
            json.put("sound_name", soundName);
            json.put("sound_url", soundUrl);
            json.put("local_sound_uri", localSoundUri);
            json.put("sound_revision", soundRevision);
        } catch (Exception ignored) {}
        return json;
    }

    private static String first(JSONObject json, String... keys) {
        for (String key : keys) {
            String value = json.optString(key, "");
            if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) return value;
        }
        return "";
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
