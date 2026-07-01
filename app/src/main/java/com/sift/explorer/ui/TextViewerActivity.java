package com.sift.explorer.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.sift.explorer.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lightweight text viewer/editor. Editing+save is enabled for local files. */
public class TextViewerActivity extends AppCompatActivity {

    private static final String EXTRA_PATH = "path", EXTRA_NAME = "name", EXTRA_EDIT = "edit";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText editor;
    private File file;
    private boolean editable;

    public static void open(Context ctx, String path, String name, boolean editable) {
        Intent i = new Intent(ctx, TextViewerActivity.class);
        i.putExtra(EXTRA_PATH, path);
        i.putExtra(EXTRA_NAME, name);
        i.putExtra(EXTRA_EDIT, editable);
        ctx.startActivity(i);
    }

    @Override protected void onCreate(Bundle s) {
        setTheme(com.sift.explorer.util.ThemePrefs.themeRes(this));
        super.onCreate(s);
        setContentView(R.layout.activity_text_viewer);
        file = new File(getIntent().getStringExtra(EXTRA_PATH));
        editable = getIntent().getBooleanExtra(EXTRA_EDIT, false);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(getIntent().getStringExtra(EXTRA_NAME));
        toolbar.setNavigationOnClickListener(v -> finish());
        if (editable) {
            toolbar.inflateMenu(R.menu.text_menu);
            toolbar.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == R.id.action_save) { save(); return true; }
                return false;
            });
        }

        editor = findViewById(R.id.editor);
        editor.setEnabled(editable);
        load();
    }

    private void load() {
        io.execute(() -> {
            String text;
            try {
                InputStream in = new FileInputStream(file);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                in.close();
                text = bos.toString("UTF-8");
            } catch (Exception e) {
                text = "Could not read file: " + e.getMessage();
            }
            final String t = text;
            main.post(() -> editor.setText(t));
        });
    }

    private void save() {
        final String text = editor.getText().toString();
        io.execute(() -> {
            boolean ok = true; String err = null;
            try {
                FileOutputStream out = new FileOutputStream(file);
                out.write(text.getBytes("UTF-8"));
                out.close();
            } catch (Exception e) { ok = false; err = e.getMessage(); }
            final boolean fok = ok; final String ferr = err;
            main.post(() -> Toast.makeText(this, fok ? "Saved" : "Save failed: " + ferr, Toast.LENGTH_SHORT).show());
        });
    }

    @Override protected void onDestroy() { super.onDestroy(); io.shutdownNow(); }
}
