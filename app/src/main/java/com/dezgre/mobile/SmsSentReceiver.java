package com.dezgre.mobile;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class SmsSentReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String commandId = intent == null ? "" : intent.getStringExtra(SmsGatewayRuntime.EXTRA_COMMAND_ID);
        if (commandId == null || commandId.trim().isEmpty()) return;
        boolean success = getResultCode() == Activity.RESULT_OK;
        if (!success) {
            if (!SmsGatewayPartTracker.failed(context, commandId)) {
                SmsGatewayRuntime.reportStatus(context, commandId, "FAILED", "La operadora rechazó el envío");
            }
            SmsGatewayPartTracker.recordSent(context, commandId, false);
            return;
        }
        if (SmsGatewayPartTracker.recordSent(context, commandId, true)) {
            String destination = intent.getStringExtra(SmsGatewayRuntime.EXTRA_DESTINATION);
            String body = intent.getStringExtra(SmsGatewayRuntime.EXTRA_BODY);
            SmsGatewayRuntime.insertSentIntoProvider(context, destination == null ? "" : destination, body == null ? "" : body);
            SmsGatewayRuntime.reportStatus(context, commandId, "SENT", "");
        }
    }
}
