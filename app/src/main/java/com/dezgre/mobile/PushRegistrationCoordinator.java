package com.dezgre.mobile;

import android.content.Context;

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
        if (cleanBearer.isEmpty()) {
            complete(callback, false, "NO_BEARER");
            return;
        }
        if (!isFirebaseConfigured(app)) {
            complete(callback, false, "FIREBASE_NOT_CONFIGURED");
            return;
        }

        try {
            // Backend contract currently requires the FCM registration token.
            // getToken() remains supported by the SDK even though newer Firebase releases
            // recommend FID-based registration for new backends.
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (!task.isSuccessful()) {
                    complete(callback, false, "FCM_TOKEN_FAILED");
                    return;
                }
                String token = clean(task.getResult());
                if (token.isEmpty() || !MobilePushRegistration.acceptTransportToken(app, token)) {
                    complete(callback, false, "FCM_TOKEN_EMPTY");
                    return;
                }
                registerStoredToken(app, cleanBearer, callback);
            });
        } catch (Exception ignored) {
            complete(callback, false, "FCM_UNAVAILABLE");
        }
    }

    static void onTokenRotated(Context context, String token) {
        final Context app = context.getApplicationContext();
        if (!MobilePushRegistration.acceptTransportToken(app, token)) return;
        String bearer = new SecureTokenStore(app).load();
        if (clean(bearer).isEmpty()) return;
        registerStoredToken(app, bearer, null);
    }

    static void registerStoredToken(Context context, String bearer, Callback callback) {
        final Context app = context.getApplicationContext();
        final String cleanBearer = clean(bearer);
        JSONObject payload = MobilePushRegistration.buildRegistrationPayload(app);
        if (cleanBearer.isEmpty() || payload == null) {
            complete(callback, false, "REGISTRATION_NOT_READY");
            return;
        }

        final ApiClient api = new ApiClient();
        api.put(DEVICE_ENDPOINT, cleanBearer, payload, new ApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject json) {
                MobilePushRegistration.markRegistered(app, cleanBearer);
                api.shutdown();
                complete(callback, true, "REGISTERED");
            }

            @Override
            public void onError(ApiClient.ApiException error) {
                MobilePushRegistration.invalidateRegistration(app, cleanBearer);
                api.shutdown();
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

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static void complete(Callback callback, boolean success, String code) {
        if (callback != null) callback.onComplete(success, code == null ? "" : code);
    }
}
