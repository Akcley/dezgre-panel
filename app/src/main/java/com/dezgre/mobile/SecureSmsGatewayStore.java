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

final class SecureSmsGatewayStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "dezgre_mobile_sms_gateway_v1";
    private static final String PREFS = "dezgre_mobile_sms_gateway_secure_v1";
    private static final String TOKEN_KEY = "gateway_token";
    private static final String GATEWAY_ID_KEY = "gateway_id";
    private static final String STORE_ID_KEY = "store_id";

    private final SharedPreferences preferences;

    SecureSmsGatewayStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized void save(String token, long gatewayId, long storeId) throws Exception {
        String clean = token == null ? "" : token.trim();
        if (clean.isEmpty() || gatewayId <= 0 || storeId <= 0) {
            throw new IllegalArgumentException("Invalid SMS gateway credentials");
        }
        preferences.edit()
                .putString(TOKEN_KEY, encrypt(clean))
                .putLong(GATEWAY_ID_KEY, gatewayId)
                .putLong(STORE_ID_KEY, storeId)
                .commit();
    }

    synchronized String token() {
        String stored = preferences.getString(TOKEN_KEY, "");
        if (stored == null || stored.isEmpty()) return "";
        try {
            String[] parts = stored.split(":", 2);
            if (parts.length != 2) return "";
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] data = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            clear();
            return "";
        }
    }

    synchronized long gatewayId() {
        return preferences.getLong(GATEWAY_ID_KEY, 0L);
    }

    synchronized long storeId() {
        return preferences.getLong(STORE_ID_KEY, 0L);
    }

    synchronized boolean isPaired() {
        return gatewayId() > 0 && storeId() > 0 && !token().isEmpty();
    }

    synchronized void clear() {
        preferences.edit().clear().commit();
    }

    private String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
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
}
