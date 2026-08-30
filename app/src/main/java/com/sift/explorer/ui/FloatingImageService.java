package com.sift.explorer.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sift.explorer.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds the pop-out image windows.
 *
 * <p>The windows are {@code TYPE_APPLICATION_OVERLAY}, so they float over other apps and outlive
 * the activity that asked for them — which is the whole point of the feature, and also why this
 * has to be a foreground service: a background one would be killed with the windows still on
 * screen. The notification is how you get rid of them all at once; each window also has its own ×.
 */
public class FloatingImageService extends Service {

    public static final String ACTION_SHOW = "com.sift.explorer.action.FLOAT_SHOW";
    public static final String ACTION_CLOSE_ALL = "com.sift.explorer.action.FLOAT_CLOSE_ALL";
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_NAME = "name";

    /** Past this many, the screen is the problem, not the feature. */
    private static final int MAX_WINDOWS = 6;
    private static final String CHANNEL_ID = "floating_images";
    private static final int NOTIFICATION_ID = 42;

    private final List<FloatingImageWindow> windows = new ArrayList<>();
    private Context themed;

    /** Whether the "Display over other apps" permission has been granted. */
    public static boolean canFloat(Context ctx) {
        return Settings.canDrawOverlays(ctx);
    }

    /** The settings screen that grants it. */
    public static Intent permissionIntent(Context ctx) {
        return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + ctx.getPackageName()));
    }

    /** Float {@code file} in a new window. The file must already be local. */
    public static void show(Context ctx, File file, String name) {
        Intent i = new Intent(ctx, FloatingImageService.class)
                .setAction(ACTION_SHOW)
                .putExtra(EXTRA_PATH, file.getAbsolutePath())
                .putExtra(EXTRA_NAME, name);
        ctx.startForegroundService(i);
    }

    @Override public void onCreate() {
        super.onCreate();
        // A Service context carries no theme, and the window layout resolves theme attributes.
        themed = new ContextThemeWrapper(this, R.style.Theme_Sift);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // startForeground must happen on every start, before anything can fail.
        startInForeground();

        String action = intent == null ? null : intent.getAction();
        if (ACTION_CLOSE_ALL.equals(action)) { closeAll(); return START_NOT_STICKY; }

        if (ACTION_SHOW.equals(action)) {
            String path = intent.getStringExtra(EXTRA_PATH);
            String name = intent.getStringExtra(EXTRA_NAME);
            if (path != null && canFloat(this)) {
                if (windows.size() >= MAX_WINDOWS) {
                    Toast.makeText(this, "Close a floating image first (" + MAX_WINDOWS + " open)",
                            Toast.LENGTH_SHORT).show();
                } else {
                    FloatingImageWindow w = new FloatingImageWindow(
                            themed, new File(path), name == null ? "" : name, windows.size());
                    w.setOnClose(() -> onWindowClosed(w));
                    if (w.add()) {
                        windows.add(w);
                        updateNotification();
                    }
                }
            }
        }

        if (windows.isEmpty()) { stopSelf(); return START_NOT_STICKY; }
        return START_NOT_STICKY;
    }

    private void onWindowClosed(FloatingImageWindow w) {
        windows.remove(w);
        if (windows.isEmpty()) stopSelf();
        else updateNotification();
    }

    private void closeAll() {
        // close() calls back into onWindowClosed, so iterate a copy.
        for (FloatingImageWindow w : new ArrayList<>(windows)) w.close();
        windows.clear();
        stopSelf();
    }

    @Override public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // A rotation can leave a window off the edge of the new screen.
        for (FloatingImageWindow w : windows) w.clampIntoScreen();
    }

    @Override public void onDestroy() {
        for (FloatingImageWindow w : new ArrayList<>(windows)) w.close();
        windows.clear();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    // ---- notification ----------------------------------------------------

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Floating images", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Shown while an image is floating over other apps.");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private void startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        int n = Math.max(1, windows.size());
        PendingIntent closeAll = PendingIntent.getService(this, 0,
                new Intent(this, FloatingImageService.class).setAction(ACTION_CLOSE_ALL),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_type_image)
                .setContentTitle(n == 1 ? "1 floating image" : n + " floating images")
                .setContentText("Tap Close all to dismiss them.")
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_close), "Close all", closeAll).build())
                .setOngoing(true)
                .build();
    }
}
