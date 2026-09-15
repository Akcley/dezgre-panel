package com.dezgre.mobile;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.json.JSONObject;

final class PushDiagnosticsStore {
    private static final String PREFS = "dezgre_push_diagnostics_v1";
    private final Context app;
    private final SharedPreferences prefs;

    PushDiagnosticsStore(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void recordPermission(boolean granted) {
        prefs.edit().putBoolean("permission_granted", granted).apply();
    }

    void recordFcmToken(boolean present) {
        prefs.edit().putBoolean("fcm_token_present", present).apply();
    }

    void recordPutAttempt(String deviceId) {
        prefs.edit()
                .putBoolean("put_attempted", true)
                .putString("device_id", safe(deviceId))
                .putLong("put_attempted_at", System.currentTimeMillis())
                .apply();
    }

    void recordPutSuccess(int httpStatus, JSONObject response) {
        prefs.edit()
                .putBoolean("put_success", true)
                .putInt("put_http_status", httpStatus)
                .putString("put_code", "HTTP_" + httpStatus)
                .putString("put_response", sanitizeResponse(response))
                .putLong("put_completed_at", System.currentTimeMillis())
                .apply();
    }

    void recordPutFailure(int httpStatus, String code, String message) {
        prefs.edit()
                .putBoolean("put_success", false)
                .putInt("put_http_status", httpStatus)
                .putString("put_code", safe(code))
                .putString("put_response", safe(message))
                .putLong("put_completed_at", System.currentTimeMillis())
                .apply();
    }

    void recordStoreScope(String storeId, String storeName) {
        prefs.edit()
                .putString("store_id", safe(storeId))
                .putString("store_name", safe(storeName))
                .apply();
    }

    void recordFirebaseServiceState() {
        prefs.edit().putBoolean("firebase_service_active", isFirebaseServiceActive(app)).apply();
    }

    boolean isRegisteredPass() {
        return prefs.getBoolean("fcm_token_present", false)
                && prefs.getBoolean("put_success", false)
                && prefs.getInt("put_http_status", 0) >= 200
                && prefs.getInt("put_http_status", 0) < 300;
    }

    String summary() {
        String storeId = prefs.getString("store_id", "");
        String storeName = prefs.getString("store_name", "");
        String store = !safe(storeName).isEmpty() ? safe(storeName) : safe(storeId);
        if (store.isEmpty()) store = "scope pendiente";
        return "FCM token=" + yesNo(prefs.getBoolean("fcm_token_present", false))
                + " · permiso=" + yesNo(DezgreNotificationManager.hasPermission(app))
                + " · PUT=" + yesNo(prefs.getBoolean("put_attempted", false))
                + " · HTTP=" + prefs.getInt("put_http_status", 0)
                + " · tienda=" + store
                + " · service=" + yesNo(isFirebaseServiceActive(app));
    }

    int httpStatus() {
        return prefs.getInt("put_http_status", 0);
    }

    String httpResponse() {
        return prefs.getString("put_response", "");
    }

    String deviceId() {
        String stored = prefs.getString("device_id", "");
        if (!safe(stored).isEmpty()) return safe(stored);
        return new SecurePushStore(app).getOrCreateDeviceId();
    }

    String storeScopeLabel() {
        String name = safe(prefs.getString("store_name", ""));
        String id = safe(prefs.getString("store_id", ""));
        if (!name.isEmpty() && !id.isEmpty()) return name + " (" + id + ")";
        if (!name.isEmpty()) return name;
        if (!id.isEmpty()) return id;
        return "pendiente";
    }

    static boolean isFirebaseServiceActive(Context context) {
        try {
            ComponentName component = new ComponentName(context, DezgreFirebaseMessagingService.class);
            context.getPackageManager().getServiceInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS);
            int state = context.getPackageManager().getComponentEnabledSetting(component);
            return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String sanitizeResponse(JSONObject response) {
        if (response == null) return "{}";
        try {
            JSONObject safe = new JSONObject(response.toString());
            safe.remove("pushToken");
            safe.remove("access_token");
            safe.remove("token");
            safe.remove("bearer");
            return safe.toString();
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private static String yesNo(boolean value) {
        return value ? "SI" : "NO";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
