package com.dezgre.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecurePushStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "dezgre_mobile_push_token_v1";
    private static final String PREFS = "dezgre_mobile_push_secure_v1";
    private static final String TOKEN_KEY = "push_token";
    private static final String DEVICE_ID_KEY = "device_id";
    private static final String REGISTERED_HASH_KEY = "registered_token_hash";

    private final SharedPreferences preferences;

    SecurePushStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized void savePushToken(String token) throws Exception {
        String clean = token == null ? "" : token.trim();
        if (clean.isEmpty()) {
            preferences.edit().remove(TOKEN_KEY).remove(REGISTERED_HASH_KEY).apply();
            return;
        }
        saveEncrypted(TOKEN_KEY, clean);
        if (!tokenHash(clean).equals(preferences.getString(REGISTERED_HASH_KEY, ""))) {
            preferences.edit().remove(REGISTERED_HASH_KEY).apply();
        }
    }

    synchronized String loadPushToken() {
        return loadEncrypted(TOKEN_KEY);
    }

    synchronized String getOrCreateDeviceId() {
        String existing = preferences.getString(DEVICE_ID_KEY, "");
        if (existing != null && !existing.trim().isEmpty()) return existing.trim();
        String generated = UUID.randomUUID().toString();
        preferences.edit().putString(DEVICE_ID_KEY, generated).commit();
        return generated;
    }

    synchronized boolean needsRegistration() {
        String token = loadPushToken();
        if (token == null || token.isEmpty()) return false;
        return !tokenHash(token).equals(preferences.getString(REGISTERED_HASH_KEY, ""));
    }

    synchronized void markRegistered() {
        String token = loadPushToken();
        if (token == null || token.isEmpty()) return;
        preferences.edit().putString(REGISTERED_HASH_KEY, tokenHash(token)).apply();
    }

    synchronized void invalidateRegistration() {
        preferences.edit().remove(REGISTERED_HASH_KEY).apply();
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

    private void saveEncrypted(String key, String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        String stored = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        preferences.edit().putString(key, stored).commit();
    }

    private String loadEncrypted(String key) {
        String stored = preferences.getString(key, null);
        if (stored == null || stored.isEmpty()) return null;
        try {
            String[] parts = stored.split(":", 2);
            if (parts.length != 2) return null;
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] data = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            preferences.edit().remove(key).remove(REGISTERED_HASH_KEY).apply();
            return null;
        }
    }

    private static String tokenHash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(digest, Base64.NO_WRAP | Base64.URL_SAFE);
        } catch (Exception ignored) {
            return "";
        }
    }
}
