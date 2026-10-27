package com.sift.explorer.api;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.sift.explorer.R;

/**
 * The Local API panel — the only place the socket can be opened from. The switch takes effect
 * immediately rather than on OK (a socket you think you closed and didn't is the worst outcome
 * here), the key is generated, and the network option says out loud what it changes.
 */
public final class ApiSettingsDialog {

    private ApiSettingsDialog() {}

    public static void show(Activity act, Runnable onChange) {
        final Context c = act;
        View v = act.getLayoutInflater().inflate(R.layout.dialog_api, null);
        MaterialSwitch enable = v.findViewById(R.id.apiEnable);
        MaterialSwitch lan = v.findViewById(R.id.apiLan);
        TextView status = v.findViewById(R.id.apiStatus);
        TextView key = v.findViewById(R.id.apiKey);
        EditText port = v.findViewById(R.id.apiPort);

        Runnable render = () -> {
            String err = ApiStatus.error();
            if (ApiStatus.running()) {
                status.setText("Listening on " + ApiStatus.address() + "\nGET /api/docs is the full reference.");
            } else if (ApiPrefs.enabled(c) && err != null) {
                status.setText("Could not listen on port " + ApiPrefs.port(c) + ": " + err);
            } else {
                status.setText(ApiPrefs.enabled(c) ? "Starting…" : "Stopped");
            }
            String k = ApiPrefs.key(c);
            key.setText(k.isEmpty() ? "None yet — one is made when you switch the API on." : k);
            if (onChange != null) onChange.run();
        };
        Runnable listener = () -> act.runOnUiThread(render);
        ApiStatus.addListener(listener);

        enable.setChecked(ApiPrefs.enabled(c));
        lan.setChecked(ApiPrefs.lan(c));
        port.setText(String.valueOf(ApiPrefs.port(c)));
        render.run();

        enable.setOnCheckedChangeListener((b, on) -> {
            ApiPrefs.setEnabled(c, on);
            if (on) ApiService.startIfEnabled(c); else ApiService.stop(c);
            render.run();
        });
        lan.setOnCheckedChangeListener((b, on) -> {
            ApiPrefs.setLan(c, on);
            // A different bind address needs a different socket.
            if (ApiPrefs.enabled(c)) ApiService.restart(c);
        });
        v.findViewById(R.id.apiCopy).setOnClickListener(x -> {
            String k = ApiPrefs.key(c);
            if (k.isEmpty()) return;
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Sift API key", k));
            Toast.makeText(c, "Key copied", Toast.LENGTH_SHORT).show();
        });
        v.findViewById(R.id.apiRegenerate).setOnClickListener(x -> {
            ApiPrefs.regenerateKey(c);
            render.run();
            Toast.makeText(c, "New key made — the old one no longer works", Toast.LENGTH_SHORT).show();
        });

        new MaterialAlertDialogBuilder(act)
                .setTitle("Local API")
                .setView(v)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    // The port is the one typed field, so it is the one that can be wrong.
                    int typed;
                    try { typed = Integer.parseInt(port.getText().toString().trim()); }
                    catch (NumberFormatException e) { typed = -1; }
                    if (typed < ApiPrefs.MIN_PORT || typed > ApiPrefs.MAX_PORT) {
                        Toast.makeText(c, "Port must be 1024–65535", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (typed != ApiPrefs.port(c)) {
                        ApiPrefs.setPort(c, typed);
                        if (ApiPrefs.enabled(c)) ApiService.restart(c);
                    }
                })
                .setOnDismissListener(d -> { ApiStatus.removeListener(listener); if (onChange != null) onChange.run(); })
                .show();
    }
}
