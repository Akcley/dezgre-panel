package com.dezgre.mobile;

import android.content.Context;
import android.content.SharedPreferences;

final class SmsGatewayPartTracker {
    private static final String PREFS = "dezgre_sms_gateway_parts_v1";

    private SmsGatewayPartTracker() {}

    static void prepare(Context context, String commandId, int total) {
        prefs(context).edit()
                .putInt(commandId + ".total", Math.max(1, total))
                .putInt(commandId + ".sent", 0)
                .putInt(commandId + ".delivered", 0)
                .putBoolean(commandId + ".failed", false)
                .apply();
    }

    static boolean recordSent(Context context, String commandId, boolean success) {
        synchronized (SmsGatewayPartTracker.class) {
            SharedPreferences p = prefs(context);
            if (!success) {
                p.edit().putBoolean(commandId + ".failed", true).apply();
                return false;
            }
            int sent = p.getInt(commandId + ".sent", 0) + 1;
            p.edit().putInt(commandId + ".sent", sent).apply();
            return !p.getBoolean(commandId + ".failed", false)
                    && sent >= p.getInt(commandId + ".total", 1);
        }
    }

    static boolean recordDelivered(Context context, String commandId, boolean success) {
        synchronized (SmsGatewayPartTracker.class) {
            SharedPreferences p = prefs(context);
            if (!success) {
                p.edit().putBoolean(commandId + ".failed", true).apply();
                return false;
            }
            int delivered = p.getInt(commandId + ".delivered", 0) + 1;
            p.edit().putInt(commandId + ".delivered", delivered).apply();
            return !p.getBoolean(commandId + ".failed", false)
                    && delivered >= p.getInt(commandId + ".total", 1);
        }
    }

    static boolean failed(Context context, String commandId) {
        return prefs(context).getBoolean(commandId + ".failed", false);
    }

    static void clear(Context context, String commandId) {
        prefs(context).edit()
                .remove(commandId + ".total")
                .remove(commandId + ".sent")
                .remove(commandId + ".delivered")
                .remove(commandId + ".failed")
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
