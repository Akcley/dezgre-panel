package com.dezgre.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Telephony;
import android.widget.Toast;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONObject;

public final class SmsPairingActivity extends Activity {
    private static final int REQUEST_SMS_ROLE = 7302;
    private static final int REQUEST_SMS_PERMISSIONS = 7303;

    private final ApiClient api = new ApiClient();
    private boolean scannerStarted = false;
    private boolean pairedThisRun = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startScanner();
    }

    private void startScanner() {
        if (scannerStarted || isFinishing()) return;
        scannerStarted = true;
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt("Escanea el QR de SMS Web mostrado en DEZGRE");
        integrator.setBeepEnabled(false);
        integrator.setBarcodeImageEnabled(false);
        integrator.setOrientationLocked(true);
        integrator.initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_SMS_ROLE) {
            super.onActivityResult(requestCode, resultCode, data);
            requestSmsPermissions();
            return;
        }

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
        pairWithFcm(pairToken);
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

    private void pairWithFcm(String pairToken) {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || clean(task.getResult()).isEmpty()) {
                humanError("No se pudo preparar este teléfono para SMS Web.");
                return;
            }
            String pushToken = clean(task.getResult());
            MobilePushRegistration.acceptTransportToken(this, pushToken);
            pair(pairToken, pushToken);
        });
    }

    private void pair(String pairToken, String pushToken) {
        JSONObject body = new JSONObject();
        try {
            body.put("pairToken", pairToken);
            body.put("deviceId", MobilePushRegistration.deviceId(this));
            body.put("deviceName", deviceName());
            body.put("appVersion", appVersion());
            body.put("pushToken", pushToken);
            body.put("smsPermissionGranted", SmsGatewayRuntime.hasSendPermission(this));
            body.put("receivePermissionGranted", SmsGatewayRuntime.hasReceivePermission(this));
            body.put("defaultSms", SmsGatewayRuntime.isDefaultSmsApp(this));
        } catch (Exception error) {
            humanError("No se pudo preparar este teléfono para SMS Web.");
            return;
        }

        api.post("/sms/pair", null, body, new ApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject json) {
                try {
                    String gatewayToken = clean(json.optString("gatewayToken", ""));
                    JSONObject device = json.optJSONObject("device");
                    long gatewayId = device == null ? 0L : device.optLong("id", 0L);
                    long storeId = device == null ? 0L : device.optLong("storeId", 0L);
                    if (gatewayToken.isEmpty() || gatewayId <= 0 || storeId <= 0) {
                        throw new IllegalStateException("Invalid SMS gateway response");
                    }
                    new SecureSmsGatewayStore(SmsPairingActivity.this).save(gatewayToken, gatewayId, storeId);
                    pairedThisRun = true;
                    runOnUiThread(() -> ensureSmsRole());
                } catch (Exception storageError) {
                    humanError("No se pudo guardar la conexión SMS Web en este teléfono.");
                }
            }

            @Override
            public void onError(ApiClient.ApiException error) {
                String message = error != null && error.status == 409
                        ? "El QR venció o ya fue utilizado. Genera uno nuevo."
                        : "No se pudo vincular este teléfono. Inténtalo nuevamente.";
                humanError(message);
            }
        });
    }

    private void ensureSmsRole() {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                RoleManager manager = (RoleManager) getSystemService(ROLE_SERVICE);
                if (manager != null && manager.isRoleAvailable(RoleManager.ROLE_SMS)
                        && !manager.isRoleHeld(RoleManager.ROLE_SMS)) {
                    startActivityForResult(manager.createRequestRoleIntent(RoleManager.ROLE_SMS), REQUEST_SMS_ROLE);
                    return;
                }
            } catch (Exception ignored) {}
        } else if (!SmsGatewayRuntime.isDefaultSmsApp(this)) {
            try {
                Intent intent = new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT);
                intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, getPackageName());
                startActivityForResult(intent, REQUEST_SMS_ROLE);
                return;
            } catch (Exception ignored) {}
        }
        requestSmsPermissions();
    }

    private void requestSmsPermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            boolean send = checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
            boolean receive = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
            if (!send || !receive) {
                requestPermissions(
                        new String[]{Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS},
                        REQUEST_SMS_PERMISSIONS
                );
                return;
            }
        }
        finishPairingSetup();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_SMS_PERMISSIONS) finishPairingSetup();
    }

    private void finishPairingSetup() {
        String pushToken = new SecurePushStore(this).loadPushToken();
        SmsGatewayRuntime.heartbeat(this, pushToken == null ? "" : pushToken);
        if (pairedThisRun) {
            if (SmsGatewayRuntime.hasSendPermission(this) && SmsGatewayRuntime.hasReceivePermission(this)) {
                Toast.makeText(this, "SMS Web conectado", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this,
                        "SMS Web conectado. Autoriza SMS para enviar y recibir mensajes.",
                        Toast.LENGTH_LONG).show();
            }
        }
        finish();
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
