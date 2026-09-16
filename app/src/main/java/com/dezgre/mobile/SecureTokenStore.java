package com.dezgre.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureTokenStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    // Keep the existing alias/prefs so APK updates reuse the same Android Keystore material.
    private static final String KEY_ALIAS = "dezgre_mobile_api_v1_token";
    private static final String PREFS = "dezgre_mobile_secure";
    private static final String TOKEN_KEY = "api_v1_bearer";
    private static final String REFRESH_KEY = "api_v1_refresh";
    private static final String ACCESS_EXPIRES_AT_KEY = "api_v1_access_expires_at";
    private static final String REFRESH_EXPIRES_AT_KEY = "api_v1_refresh_expires_at";

    public static final class Session {
        public final String accessToken;
        public final String refreshToken;
        public final long accessExpiresAtMs;
        public final long refreshExpiresAtMs;

        Session(String accessToken, String refreshToken, long accessExpiresAtMs, long refreshExpiresAtMs) {
            this.accessToken = clean(accessToken);
            this.refreshToken = clean(refreshToken);
            this.accessExpiresAtMs = accessExpiresAtMs;
            this.refreshExpiresAtMs = refreshExpiresAtMs;
        }

        public boolean hasAccess() { return !accessToken.isEmpty(); }
        public boolean hasRefresh() { return !refreshToken.isEmpty(); }
        public boolean hasAny() { return hasAccess() || hasRefresh(); }
    }

    private final SharedPreferences preferences;

    public SecureTokenStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    // Backward-compatible access-token save. It intentionally leaves an existing refresh token intact.
    public synchronized void save(String token) throws Exception {
        String clean = clean(token);
        if (clean.isEmpty()) {
            clearAccessOnly();
            return;
        }
        preferences.edit()
                .putString(TOKEN_KEY, encrypt(clean))
                .commit();
    }

    public synchronized void saveSession(
            String accessToken,
            String refreshToken,
            long accessExpiresInSeconds,
            long refreshExpiresInSeconds
    ) throws Exception {
        String access = clean(accessToken);
        String refresh = clean(refreshToken);
        if (access.isEmpty()) throw new IllegalArgumentException("Missing access token");

        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = preferences.edit()
                .putString(TOKEN_KEY, encrypt(access))
                .putLong(ACCESS_EXPIRES_AT_KEY, expiryMs(now, accessExpiresInSeconds));
        if (refresh.isEmpty()) {
            editor.remove(REFRESH_KEY).remove(REFRESH_EXPIRES_AT_KEY);
        } else {
            editor.putString(REFRESH_KEY, encrypt(refresh))
                    .putLong(REFRESH_EXPIRES_AT_KEY, expiryMs(now, refreshExpiresInSeconds));
        }
        if (!editor.commit()) throw new IllegalStateException("Session persistence failed");
    }

    public synchronized Session loadSession() {
        String access = loadEncrypted(TOKEN_KEY, false);
        String refresh = loadEncrypted(REFRESH_KEY, false);
        long accessExpires = preferences.getLong(ACCESS_EXPIRES_AT_KEY, 0L);
        long refreshExpires = preferences.getLong(REFRESH_EXPIRES_AT_KEY, 0L);
        return new Session(access, refresh, accessExpires, refreshExpires);
    }

    public synchronized String load() {
        Session session = loadSession();
        return session.hasAccess() ? session.accessToken : null;
    }

    public synchronized void clearAccessOnly() {
        preferences.edit().remove(TOKEN_KEY).remove(ACCESS_EXPIRES_AT_KEY).commit();
    }

    public synchronized void clear() {
        preferences.edit()
                .remove(TOKEN_KEY)
                .remove(REFRESH_KEY)
                .remove(ACCESS_EXPIRES_AT_KEY)
                .remove(REFRESH_EXPIRES_AT_KEY)
                .commit();
    }

    private String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private String loadEncrypted(String key, boolean clearAllOnFailure) {
        String stored = preferences.getString(key, null);
        if (stored == null || stored.isEmpty()) return "";
        try {
            String[] parts = stored.split(":", 2);
            if (parts.length != 2) throw new IllegalStateException("Bad encrypted token");
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] data = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        } catch (Exception error) {
            if (clearAllOnFailure) {
                clear();
            } else {
                SharedPreferences.Editor editor = preferences.edit().remove(key);
                if (TOKEN_KEY.equals(key)) editor.remove(ACCESS_EXPIRES_AT_KEY);
                if (REFRESH_KEY.equals(key)) editor.remove(REFRESH_EXPIRES_AT_KEY);
                editor.commit();
            }
            return "";
        }
    }

    private static long expiryMs(long now, long seconds) {
        if (seconds <= 0) return 0L;
        long delta;
        try {
            delta = Math.multiplyExact(seconds, 1000L);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
        if (Long.MAX_VALUE - now < delta) return Long.MAX_VALUE;
        return now + delta;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
