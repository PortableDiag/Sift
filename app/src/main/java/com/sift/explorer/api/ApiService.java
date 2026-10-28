package com.sift.explorer.api;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.sift.explorer.MainActivity;
import com.sift.explorer.R;

/**
 * Hosts the local API for as long as it is switched on, in its own foreground service: the API
 * has to answer when no screen is open, and a socket that is open should never be a thing you
 * have to go looking for — the notification names the address, and has Stop on it.
 */
public class ApiService extends Service {

    private static final String TAG = "SiftApi";
    private static final String CHANNEL_ID = "sift_api";
    private static final int NOTIFICATION_ID = 4207;
    static final String ACTION_STOP = "com.sift.explorer.api.STOP";

    private ApiServer server;

    /** Starts the API if it is enabled; a no-op otherwise, so callers needn't check first. */
    public static void startIfEnabled(Context c) {
        if (!ApiPrefs.enabled(c)) return;
        try { ContextCompat.startForegroundService(c, new Intent(c, ApiService.class)); }
        catch (Exception e) { Log.w(TAG, "could not start the API service", e); }
    }

    public static void stop(Context c) {
        try { c.stopService(new Intent(c, ApiService.class)); } catch (Exception ignore) {}
    }

    /** Stop and start, for a port or LAN change that needs a fresh socket. */
    public static void restart(Context c) {
        stop(c);
        startIfEnabled(c);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // The notification's own Stop turns the setting off too, or the next launch reopens it.
            ApiPrefs.setEnabled(this, false);
            ApiStatus.update(false, "", null);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ApiPrefs.enabled(this)) { stopSelf(); return START_NOT_STICKY; }
        if (server != null && server.isRunning()) return START_STICKY;

        String address = ApiPrefs.bindAddress(this);
        int port = ApiPrefs.port(this);
        // PhoneAPI's key goes if PhoneAPI has gone, or is no longer its pinned release build.
        PhoneApiAccess.revokeIfGone(this);
        ApiRouter router = new ApiRouter(new SiftBackend(this), () -> ApiPrefs.key(this),
                () -> PhoneApiAccess.grant(this));
        ApiServer api = new ApiServer(router, address, port);

        // Foreground first: a service that binds before posting its notification can be killed with the socket open.
        startForegroundWith(address + ":" + port);
        try {
            api.start();
            server = api;
            ApiStatus.update(true, api.address(), null);
            return START_STICKY;
        } catch (Exception e) {
            Log.w(TAG, "could not listen on " + address + ":" + port, e);
            ApiStatus.update(false, "", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    @Override public void onDestroy() {
        if (server != null) server.stop();
        server = null;
        ApiStatus.update(false, "", ApiStatus.error());
        super.onDestroy();
    }

    private void startForegroundWith(String address) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Local API", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ApiService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_api)
                .setContentTitle("Sift local API is on")
                .setContentText("Listening on " + address)
                .setContentIntent(open)
                .addAction(0, "Stop", stop)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        int type = Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type);
    }
}
