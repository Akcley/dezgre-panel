package com.dezgre.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONObject;

public final class SmsPairingActivity extends Activity {
    private static final int REQUEST_SEND_SMS = 7301;

    private final ApiClient api = new ApiClient();
    private SecureTokenStore tokenStore;
    private boolean scannerStarted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tokenStore = new SecureTokenStore(this);
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.SEND_SMS}, REQUEST_SEND_SMS);
        } else {
            startScanner();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_SEND_SMS) startScanner();
    }

    private void startScanner() {
        if (scannerStarted || isFinishing()) return;
        scannerStarted = true;
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt("Escanea el QR de SMS mostrado en DEZGRE");
        integrator.setBeepEnabled(false);
        integrator.setBarcodeImageEnabled(false);
        integrator.setOrientationLocked(true);
        integrator.initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        String raw = clean(result.getContents());
        if (raw.isEmpty()) {
            finish();
            return;
        }
        String pairToken = parsePairToken(raw);
        if (pairToken.isEmpty()) {
            humanError("Este QR no pertenece a DEZGRE SMS.");
            return;
        }
        pair(pairToken, false);
    }

    private String parsePairToken(String raw) {
        try {
            Uri uri = Uri.parse(raw);
            String scheme = clean(uri.getScheme()).toLowerCase();
            String host = clean(uri.getHost()).toLowerCase();
            String path = clean(uri.getPath());
            if (!"dezgre".equals(scheme) || !"sms".equals(host) || !"/pair".equals(path)) return "";
            String token = clean(uri.getQueryParameter("token"));
            return token.matches("^[A-Za-z0-9_-]{32,160}$") ? token : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private void pair(String pairToken, boolean afterRefresh) {
        String bearer = clean(tokenStore.load());
        if (bearer.isEmpty()) {
            refreshAndPair(pairToken);
            return;
        }

        JSONObject body = new JSONObject();
        try {
            body.put("pairToken", pairToken);
            body.put("deviceId", MobilePushRegistration.deviceId(this));
            body.put("deviceName", deviceName());
            body.put("appVersion", appVersion());
            body.put("smsPermissionGranted", hasSmsPermission());
        } catch (Exception error) {
            humanError("No se pudo preparar este teléfono para SMS.");
            return;
        }

        api.post("/sms/pair", bearer, body, new ApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject json) {
                runOnUiThread(() -> {
                    if (hasSmsPermission()) {
                        Toast.makeText(SmsPairingActivity.this, "Antena SMS conectada", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(SmsPairingActivity.this,
                                "Antena conectada. Activa el permiso SMS para poder enviar mensajes.",
                                Toast.LENGTH_LONG).show();
                    }
                    finish();
                });
            }

            @Override
            public void onError(ApiClient.ApiException error) {
                if (!afterRefresh && (error.status == 401 || error.status == 403)) {
                    refreshAndPair(pairToken);
                    return;
                }
                String message = error.status == 409
                        ? "El QR venció o ya fue utilizado. Genera uno nuevo."
                        : "No se pudo vincular este teléfono. Inténtalo nuevamente.";
                humanError(message);
            }
        });
    }

    private void refreshAndPair(String pairToken) {
        MobileSessionCoordinator.refresh(
                this,
                api,
                tokenStore,
                new MobileSessionCoordinator.RefreshCallback() {
                    @Override
                    public void onSuccess(String accessToken) {
                        pair(pairToken, true);
                    }

                    @Override
                    public void onAuthRequired() {
                        humanError("Abre DEZGRE Mobile e inicia sesión antes de vincular SMS.");
                    }

                    @Override
                    public void onTemporaryFailure() {
                        humanError("No se pudo conectar con DEZGRE. Inténtalo nuevamente.");
                    }
                }
        );
    }

    private boolean hasSmsPermission() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private String deviceName() {
        String manufacturer = clean(Build.MANUFACTURER);
        String model = clean(Build.MODEL);
        if (manufacturer.isEmpty()) return model.isEmpty() ? "Android" : model;
        if (model.toLowerCase().startsWith(manufacturer.toLowerCase())) return model;
        return manufacturer + " " + model;
    }

    private String appVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (info.versionName != null && !info.versionName.trim().isEmpty()) return info.versionName.trim();
        } catch (Exception ignored) {}
        return "Android";
    }

    private void humanError(String message) {
        runOnUiThread(() -> {
            Toast.makeText(SmsPairingActivity.this, message, Toast.LENGTH_LONG).show();
            finish();
        });
    }

    @Override
    protected void onDestroy() {
        api.shutdown();
        super.onDestroy();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
