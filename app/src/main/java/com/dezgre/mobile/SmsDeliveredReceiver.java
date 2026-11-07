package com.dezgre.mobile;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class SmsDeliveredReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String commandId = intent == null ? "" : intent.getStringExtra(SmsGatewayRuntime.EXTRA_COMMAND_ID);
        if (commandId == null || commandId.trim().isEmpty()) return;
        boolean success = getResultCode() == Activity.RESULT_OK;
        if (!success) {
            if (!SmsGatewayPartTracker.failed(context, commandId)) {
                SmsGatewayRuntime.reportStatus(context, commandId, "FAILED", "El SMS no fue entregado");
            }
            SmsGatewayPartTracker.recordDelivered(context, commandId, false);
            return;
        }
        if (SmsGatewayPartTracker.recordDelivered(context, commandId, true)) {
            SmsGatewayRuntime.reportStatus(context, commandId, "DELIVERED", "");
            SmsGatewayPartTracker.clear(context, commandId);
        }
    }
}
