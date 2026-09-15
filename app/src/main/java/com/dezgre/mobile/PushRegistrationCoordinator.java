package com.dezgre.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

final class PushRegistrationCoordinator {
    static final String DEVICE_ENDPOINT = "/notifications/devices";

    interface Callback {
        void onComplete(boolean success, String code);
    }

    private PushRegistrationCoordinator() {}

    static boolean isFirebaseConfigured(Context context) {
        try {
            if (!FirebaseApp.getApps(context).isEmpty()) return true;
            return FirebaseApp.initializeApp(context) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    static void ensureRegistered(Context context, String bearer, Callback callback) {
        final Context app = context.getApplicationContext();
        final String cleanBearer = clean(bearer);
        final PushDiagnosticsStore diagnostics = new PushDiagnosticsStore(app);
        diagnostics.recordPermission(DezgreNotificationManager.hasPermission(app));
        diagnostics.recordFirebaseServiceState();
        if (cleanBearer.isEmpty()) {
            complete(callback, false, "NO_BEARER");
            return;
        }
        if (!isFirebaseConfigured(app)) {
            diagnostics.recordFcmToken(false);
            complete(callback, false, "FIREBASE_NOT_CONFIGURED");
            return;
        }

        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (!task.isSuccessful()) {
                    diagnostics.recordFcmToken(false);
                    complete(callback, false, "FCM_TOKEN_FAILED");
                    return;
                }
                String token = clean(task.getResult());
                boolean accepted = !token.isEmpty() && MobilePushRegistration.acceptTransportToken(app, token);
                diagnostics.recordFcmToken(accepted);
                if (!accepted) {
                    complete(callback, false, "FCM_TOKEN_EMPTY");
                    return;
                }
                registerStoredToken(app, cleanBearer, callback);
            });
        } catch (Exception ignored) {
            diagnostics.recordFcmToken(false);
            complete(callback, false, "FCM_UNAVAILABLE");
        }
    }

    static void onTokenRotated(Context context, String token) {
        final Context app = context.getApplicationContext();
        PushDiagnosticsStore diagnostics = new PushDiagnosticsStore(app);
        boolean accepted = MobilePushRegistration.acceptTransportToken(app, token);
        diagnostics.recordFcmToken(accepted);
        diagnostics.recordFirebaseServiceState();
        if (!accepted) return;
        String bearer = new SecureTokenStore(app).load();
        if (clean(bearer).isEmpty()) return;
        registerStoredToken(app, bearer, null);
    }

    static void registerStoredToken(Context context, String bearer, Callback callback) {
        final Context app = context.getApplicationContext();
        final String cleanBearer = clean(bearer);
        final PushDiagnosticsStore diagnostics = new PushDiagnosticsStore(app);
        JSONObject payload = MobilePushRegistration.buildRegistrationPayload(app);
        if (cleanBearer.isEmpty() || payload == null) {
            complete(callback, false, "REGISTRATION_NOT_READY");
            return;
        }

        final String deviceId = MobilePushRegistration.deviceId(app);
        diagnostics.recordPutAttempt(deviceId);
        final ApiClient api = new ApiClient();
        api.putDetailed(DEVICE_ENDPOINT, cleanBearer, payload, new ApiClient.DetailedCallback() {
            @Override
            public void onSuccess(int status, JSONObject json) {
                MobilePushRegistration.markRegistered(app, cleanBearer);
                diagnostics.recordPutSuccess(status, json);
                resolveStoreScope(app, cleanBearer, diagnostics);
                api.shutdown();
                showResult(app, true, status, diagnostics);
                complete(callback, true, "REGISTERED");
            }

            @Override
            public void onError(ApiClient.ApiException error) {
                MobilePushRegistration.invalidateRegistration(app, cleanBearer);
                diagnostics.recordPutFailure(error.status, error.code, error.getMessage());
                api.shutdown();
                showResult(app, false, error.status, diagnostics);
                complete(callback, false, error.code);
            }
        });
    }

    static void unregisterCurrent(Context context, String bearer, Callback callback) {
        final Context app = context.getApplicationContext();
        final String cleanBearer = clean(bearer);
        if (cleanBearer.isEmpty()) {
            complete(callback, false, "NO_BEARER");
            return;
        }

        String deviceId = MobilePushRegistration.deviceId(app);
        final ApiClient api = new ApiClient();
        api.delete(DEVICE_ENDPOINT + "/" + deviceId, cleanBearer, new ApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject json) {
                MobilePushRegistration.invalidateRegistration(app, cleanBearer);
                api.shutdown();
                complete(callback, true, "DELETED");
            }

            @Override
            public void onError(ApiClient.ApiException error) {
                api.shutdown();
                complete(callback, false, error.code);
            }
        });
    }

    private static void resolveStoreScope(Context app, String bearer, PushDiagnosticsStore diagnostics) {
        ApiClient scopeApi = new ApiClient();
        scopeApi.get("/me", bearer, new ApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject json) {
                JSONObject store = json.optJSONObject("store");
                if (store != null) {
                    diagnostics.recordStoreScope(store.optString("id", ""), store.optString("name", ""));
                }
                scopeApi.shutdown();
            }

            @Override
            public void onError(ApiClient.ApiException error) {
                scopeApi.shutdown();
            }
        });
    }

    private static void showResult(Context app, boolean success, int status, PushDiagnosticsStore diagnostics) {
        new Handler(Looper.getMainLooper()).post(() -> {
            String text = success
                    ? "ANDROID DEVICE PUSH REGISTERED = PASS · HTTP " + status
                    : "Push Android no registrado · HTTP " + status;
            Toast.makeText(app, text, Toast.LENGTH_LONG).show();
        });
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static void complete(Callback callback, boolean success, String code) {
        if (callback != null) callback.onComplete(success, code == null ? "" : code);
    }
}
