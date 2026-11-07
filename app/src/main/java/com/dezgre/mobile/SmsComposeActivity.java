package com.dezgre.mobile;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.widget.Toast;

public final class SmsComposeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri data = getIntent() == null ? null : getIntent().getData();
        String destination = data == null ? "" : data.getSchemeSpecificPart();
        String body = getIntent() == null ? "" : getIntent().getStringExtra("sms_body");
        if (body == null && getIntent() != null) {
            CharSequence extra = getIntent().getCharSequenceExtra(android.content.Intent.EXTRA_TEXT);
            body = extra == null ? "" : extra.toString();
        }
        if (destination == null) destination = "";
        int query = destination.indexOf('?');
        if (query >= 0) destination = destination.substring(0, query);
        if (!destination.trim().isEmpty() && body != null && !body.trim().isEmpty()
                && SmsGatewayRuntime.hasSendPermission(this)) {
            try {
                SmsManager.getDefault().sendTextMessage(destination.trim(), null, body, null, null);
                Toast.makeText(this, "SMS enviado", Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
                Toast.makeText(this, "No se pudo enviar el SMS", Toast.LENGTH_SHORT).show();
            }
        }
        finish();
    }
}
