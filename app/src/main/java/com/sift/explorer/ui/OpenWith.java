package com.sift.explorer.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.List;

/**
 * "Open with" flow with a remembered default per {@code typeKey}. Tapping a file
 * launches the remembered app directly; the picker lets the user choose another
 * and (optionally) make it the default for that type.
 */
public class OpenWith {

    /** Open using the remembered default if set, otherwise show the picker. */
    public static void open(Activity act, File file, String mime, String typeKey,
                            String typeLabel, com.sift.explorer.util.DefaultApps defaults) {
        ComponentName def = defaults.get(typeKey);
        if (def != null && resolvable(act, def)) {
            launch(act, file, mime, def);
        } else {
            showPicker(act, file, mime, typeKey, typeLabel, defaults);
        }
    }

    /** Always show the picker (used by the explicit "Open with…" action). */
    public static void picker(Activity act, File file, String mime, String typeKey,
                              String typeLabel, com.sift.explorer.util.DefaultApps defaults) {
        showPicker(act, file, mime, typeKey, typeLabel, defaults);
    }

    private static Uri uriFor(Context ctx, File file) {
        return FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", file);
    }

    private static Intent baseIntent(Uri uri, String mime) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return i;
    }

    private static boolean resolvable(Context ctx, ComponentName cn) {
        try { ctx.getPackageManager().getActivityInfo(cn, 0); return true; }
        catch (Exception e) { return false; }
    }

    private static void launch(Activity act, File file, String mime, ComponentName cn) {
        // Installing an APK needs the user to allow Sift as an install source (Android 8+).
        // Without it the package installer just silently no-ops, so ask first.
        if (isApk(mime) && !canInstallApks(act)) { promptInstallPermission(act); return; }
        try {
            Intent i = baseIntent(uriFor(act, file), mime);
            i.setComponent(cn);
            // Launch the handoff app as its own task so it stands alone in Recents
            // instead of being buried inside Sift's task. Otherwise a media player
            // keeps streaming through Sift's FileProvider and can only be stopped by
            // force-closing Sift; as its own task the user just swipes it away.
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(act, "Couldn’t open with that app", Toast.LENGTH_SHORT).show();
        }
    }

    private static boolean isApk(String mime) {
        return "application/vnd.android.package-archive".equalsIgnoreCase(mime);
    }

    private static boolean canInstallApks(Context ctx) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return true;
        return ctx.getPackageManager().canRequestPackageInstalls();
    }

    private static void promptInstallPermission(Activity act) {
        new MaterialAlertDialogBuilder(act)
                .setTitle("Allow installing apps")
                .setMessage("To install APKs, allow Sift to install unknown apps. You’ll be taken to the "
                        + "system setting — enable it, then tap the APK again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open settings", (d, w) -> {
                    try {
                        act.startActivity(new Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + act.getPackageName())));
                    } catch (Exception e) {
                        act.startActivity(new Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
                    }
                })
                .show();
    }

    private static void showPicker(Activity act, File file, String mime, String typeKey,
                                   String typeLabel, com.sift.explorer.util.DefaultApps defaults) {
        PackageManager pm = act.getPackageManager();
        Uri uri = uriFor(act, file);
        Intent probe = baseIntent(uri, mime);
        List<ResolveInfo> apps = pm.queryIntentActivities(probe, 0);
        // drop ourselves from the list
        for (int i = apps.size() - 1; i >= 0; i--) {
            if (act.getPackageName().equals(apps.get(i).activityInfo.packageName)) apps.remove(i);
        }
        if (apps.isEmpty()) {
            Toast.makeText(act, "No app can open this file", Toast.LENGTH_SHORT).show();
            return;
        }

        // A single ScrollView (checkbox on top, then app rows) so the dialog caps its
        // own height and scrolls — avoids a collapsed 0-height list in a wrap dialog.
        ScrollView scroll = new ScrollView(act);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);

        final CheckBox always = new CheckBox(act);
        always.setText("Always open " + typeLabel + " with this app");
        always.setPadding(dp(act, 20), dp(act, 12), dp(act, 20), dp(act, 12));
        list.addView(always);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(act)
                .setTitle("Open with")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .create();

        for (ResolveInfo ri : apps) {
            final ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(act, 20), dp(act, 12), dp(act, 20), dp(act, 12));
            row.setClickable(true);
            row.setBackgroundResource(selectableItemBg(act));

            ImageView icon = new ImageView(act);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(act, 40), dp(act, 40));
            ip.rightMargin = dp(act, 16);
            icon.setLayoutParams(ip);
            try { icon.setImageDrawable(ri.loadIcon(pm)); } catch (Exception ignore) {}

            TextView label = new TextView(act);
            label.setText(ri.loadLabel(pm));
            label.setTextSize(15);

            row.addView(icon);
            row.addView(label);
            row.setOnClickListener(v -> {
                if (always.isChecked()) defaults.set(typeKey, cn);
                launch(act, file, mime, cn);
                dialog.dismiss();
            });
            list.addView(row);
        }

        dialog.show();
    }

    private static int selectableItemBg(Context ctx) {
        android.util.TypedValue tv = new android.util.TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    private static int dp(Context c, int d) {
        return Math.round(c.getResources().getDisplayMetrics().density * d);
    }
}
