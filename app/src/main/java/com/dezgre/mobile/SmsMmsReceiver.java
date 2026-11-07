package com.dezgre.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class SmsMmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // MMS is declared only so Android can recognize DEZGRE Mobile as an SMS-capable handler.
        // The CRM gateway currently transports text SMS only.
    }
}
