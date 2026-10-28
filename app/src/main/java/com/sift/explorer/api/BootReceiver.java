package com.sift.explorer.api;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Puts the API back after a reboot or an update of Sift — only if it was switched on. An agent
 * given a key and a port expects both to work tomorrow, and phones reboot overnight; an update
 * kills the process and nothing restarts it until Sift is opened.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        ApiService.startIfEnabled(context);
    }
}
