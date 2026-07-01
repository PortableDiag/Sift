package com.sift.explorer.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.sift.explorer.R;
import com.sift.explorer.fs.Connection;
import com.sift.explorer.fs.ConnectionStore;

/** Add / edit an SMB or SFTP connection. */
public class ConnectionEditorDialog {

    public static void show(Context ctx, Connection existing, ConnectionStore store, Runnable onSaved) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_connection, null);
        RadioGroup type = v.findViewById(R.id.rgType);
        EditText name = v.findViewById(R.id.etName);
        EditText host = v.findViewById(R.id.etHost);
        EditText port = v.findViewById(R.id.etPort);
        EditText share = v.findViewById(R.id.etShare);
        EditText user = v.findViewById(R.id.etUser);
        EditText pass = v.findViewById(R.id.etPass);
        EditText path = v.findViewById(R.id.etPath);
        View shareRow = v.findViewById(R.id.rowShare);
        View pathRow = v.findViewById(R.id.rowPath);

        final Connection c = existing != null ? existing : new Connection();
        if (existing == null) c.type = Connection.TYPE_SMB;

        Runnable applyType = () -> {
            boolean smb = type.getCheckedRadioButtonId() == R.id.rbSmb;
            shareRow.setVisibility(smb ? View.VISIBLE : View.GONE);
            pathRow.setVisibility(smb ? View.GONE : View.VISIBLE);
            if (port.getText().toString().trim().isEmpty()) port.setHint(smb ? "445" : "22");
        };
        type.setOnCheckedChangeListener((g, id) -> applyType.run());
        type.check(Connection.TYPE_SFTP.equals(c.type) ? R.id.rbSftp : R.id.rbSmb);

        if (existing != null) {
            name.setText(nv(c.name));
            host.setText(nv(c.host));
            if (c.port > 0) port.setText(String.valueOf(c.port));
            share.setText(nv(c.share));
            user.setText(nv(c.username));
            pass.setText(nv(c.password));
            path.setText(nv(c.initialPath));
        }
        applyType.run();

        new MaterialAlertDialogBuilder(ctx)
                .setTitle(existing == null ? "Add connection" : "Edit connection")
                .setView(v)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    String h = host.getText().toString().trim();
                    if (h.isEmpty()) { Toast.makeText(ctx, "Host is required", Toast.LENGTH_SHORT).show(); return; }
                    c.type = type.getCheckedRadioButtonId() == R.id.rbSftp ? Connection.TYPE_SFTP : Connection.TYPE_SMB;
                    c.name = name.getText().toString().trim();
                    c.host = h;
                    String pt = port.getText().toString().trim();
                    c.port = pt.isEmpty() ? 0 : safeInt(pt);
                    c.share = share.getText().toString().trim();
                    c.username = user.getText().toString().trim();
                    c.password = pass.getText().toString();
                    c.initialPath = path.getText().toString().trim();
                    store.save(c);
                    if (onSaved != null) onSaved.run();
                })
                .show();
    }

    private static String nv(String s) { return s == null ? "" : s; }
    private static int safeInt(String s) { try { return Integer.parseInt(s); } catch (Exception e) { return 0; } }
}
