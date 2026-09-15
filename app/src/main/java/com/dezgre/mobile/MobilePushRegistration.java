package com.dezgre.mobile;

import android.content.Context;
import android.content.pm.PackageInfo;

import org.json.JSONObject;

final class MobilePushRegistration {
    private MobilePushRegistration() {}

    static boolean acceptTransportToken(Context context, String token) {
        try {
            new SecurePushStore(context).savePushToken(token);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    static JSONObject buildRegistrationPayload(Context context) {
        SecurePushStore store = new SecurePushStore(context);
        String token = store.loadPushToken();
        if (token == null || token.trim().isEmpty()) return null;

        JSONObject payload = new JSONObject();
        try {
            payload.put("platform", "android");
            payload.put("provider", "fcm");
            payload.put("pushToken", token.trim());
            payload.put("deviceId", store.getOrCreateDeviceId());
            payload.put("appVersion", appVersion(context));
        } catch (Exception ignored) {
            return null;
        }
        return payload;
    }

    static String deviceId(Context context) {
        return new SecurePushStore(context).getOrCreateDeviceId();
    }

    static boolean needsRegistration(Context context, String bearer) {
        return new SecurePushStore(context).needsRegistration(bearer);
    }

    static void markRegistered(Context context, String bearer) {
        new SecurePushStore(context).markRegistered(bearer);
    }

    static void invalidateRegistration(Context context, String bearer) {
        new SecurePushStore(context).invalidateRegistration(bearer);
    }

    static String statusLabel(Context context, String bearer) {
        SecurePushStore store = new SecurePushStore(context);
        String token = store.loadPushToken();
        if (token == null || token.isEmpty()) return "Esperando token FCM";
        return store.needsRegistration(bearer) ? "FCM listo · registro V1 pendiente" : "FCM registrado";
    }

    private static String appVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (info.versionName != null && !info.versionName.trim().isEmpty()) return info.versionName.trim();
        } catch (Exception ignored) {}
        return "unknown";
    }
}
