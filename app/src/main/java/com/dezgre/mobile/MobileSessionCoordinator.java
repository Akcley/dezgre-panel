package com.dezgre.mobile;

import android.content.Context;

import org.json.JSONObject;

final class MobileSessionCoordinator {
    private static final String REFRESH_ENDPOINT = "/auth/refresh";
    private static final String LOGOUT_ENDPOINT = "/auth/logout";

    interface RefreshCallback {
        void onSuccess(String accessToken);
        void onAuthRequired();
        void onTemporaryFailure();
    }

    interface LogoutCallback {
        void onComplete(boolean success);
    }

    private MobileSessionCoordinator() {}

    static String persistAuthResponse(Context context, SecureTokenStore tokenStore, JSONObject json) throws Exception {
        if (json == null) throw new IllegalArgumentException("Missing auth response");
        String access = clean(json.optString("access_token", ""));
        String refresh = clean(json.optString("refresh_token", ""));
        long accessExpires = json.optLong("expires_in", 0L);
        long refreshExpires = json.optLong("refresh_expires_in", 0L);
        if (access.isEmpty()) throw new IllegalArgumentException("Missing access token");
        tokenStore.saveSession(access, refresh, accessExpires, refreshExpires);
        return access;
    }

    static void refresh(
            Context context,
            ApiClient api,
            SecureTokenStore tokenStore,
            RefreshCallback callback
    ) {
        SecureTokenStore.Session session = tokenStore.loadSession();
        if (!session.hasRefresh()) {
            callback.onAuthRequired();
            return;
        }

        JSONObject body = new JSONObject();
        try {
            body.put("refreshToken", session.refreshToken);
            body.put("deviceId", MobilePushRegistration.deviceId(context));
        } catch (Exception ignored) {
            callback.onTemporaryFailure();
            return;
        }

        api.post(REFRESH_ENDPOINT, null, body, new ApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject json) {
                try {
                    // Refresh tokens are single-use. Persist the rotated token synchronously
                    // before any navigation or retry can happen.
                    String access = persistAuthResponse(context, tokenStore, json);
                    callback.onSuccess(access);
                } catch (Exception storageError) {
                    // The old refresh has already rotated. If secure persistence fails,
                    // the local session is no longer recoverable safely.
                    tokenStore.clear();
                    callback.onAuthRequired();
                }
            }

            @Override
            public void onError(ApiClient.ApiException error) {
                if (isTerminalRefreshFailure(error)) {
                    tokenStore.clear();
                    callback.onAuthRequired();
                } else {
                    // Network/temporary server failures must not destroy a valid refresh session.
                    callback.onTemporaryFailure();
                }
            }
        });
    }

    static void logout(
            Context context,
            ApiClient api,
            SecureTokenStore tokenStore,
            String accessToken,
            LogoutCallback callback
    ) {
        String access = clean(accessToken);
        if (access.isEmpty()) {
            tokenStore.clear();
            callback.onComplete(true);
            return;
        }

        JSONObject body = new JSONObject();
        try {
            body.put("deviceId", MobilePushRegistration.deviceId(context));
        } catch (Exception ignored) {
            callback.onComplete(false);
            return;
        }

        api.post(LOGOUT_ENDPOINT, access, body, new ApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject json) {
                MobilePushRegistration.invalidateRegistration(context, access);
                tokenStore.clear();
                callback.onComplete(true);
            }

            @Override
            public void onError(ApiClient.ApiException error) {
                // Do not erase local credentials unless the server logout succeeded.
                callback.onComplete(false);
            }
        });
    }

    private static boolean isTerminalRefreshFailure(ApiClient.ApiException error) {
        if (error == null) return false;
        return error.status == 400 || error.status == 401 || error.status == 403;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
