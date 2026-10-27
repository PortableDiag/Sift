package com.sift.explorer.api;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Puts the API back after a reboot — only if it was switched on. An agent given a key and a port
 * expects both to work tomorrow, and phones reboot overnight.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        ApiService.startIfEnabled(context);
    }
}
