package com.sift.explorer.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.sift.explorer.R;
import com.sift.explorer.fs.FileItem;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Text viewer/editor. Works on every backend: the browser hands us a local working copy plus
 * the original {@link FileItem}; Save writes the working copy and, for remote sources, streams
 * the bytes back to the share. Extras: copy-all, find, word-wrap toggle, unsaved-changes guard.
 */
public class TextViewerActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private EditText editor;
    private File local;          // local working copy we read/write
    private FileItem source;     // original item (may be remote); null for a bare local file
    private String title;

    private boolean dirty;
    private boolean loading;     // suppress dirty-tracking during programmatic setText
    private boolean wrap = true; // word wrap on by default

    // find bar
    private View findBar;
    private EditText findInput;

    /** Opens the editor for whatever was staged in {@link TextTarget}. */
    public static void open(Context ctx) {
        ctx.startActivity(new Intent(ctx, TextViewerActivity.class));
    }

    @Override protected void onCreate(Bundle s) {
        setTheme(com.sift.explorer.util.ThemePrefs.themeRes(this));
        super.onCreate(s);
        setContentView(R.layout.activity_text_viewer);

        local = TextTarget.local;
        source = TextTarget.source;
        title = TextTarget.title != null ? TextTarget.title : (local != null ? local.getName() : "Text");

        if (local == null) { Toast.makeText(this, "Nothing to open", Toast.LENGTH_SHORT).show(); finish(); return; }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> maybeFinish());
        toolbar.inflateMenu(R.menu.text_menu);
        toolbar.setOnMenuItemClickListener(this::onMenu);
        toolbar.getMenu().findItem(R.id.action_wrap).setChecked(wrap);

        editor = findViewById(R.id.editor);
        editor.setHorizontallyScrolling(!wrap);
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void afterTextChanged(Editable e) {
                if (!loading && !dirty) { dirty = true; updateTitle(); }
            }
        });

        setupFindBar();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { maybeFinish(); }
        });

        updateTitle();
        load();
    }

    private boolean onMenu(android.view.MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_save) { save(); return true; }
        if (id == R.id.action_copy_all) { copyAll(); return true; }
        if (id == R.id.action_find) { toggleFindBar(); return true; }
        if (id == R.id.action_wrap) { item.setChecked(!item.isChecked()); setWrap(item.isChecked()); return true; }
        return false;
    }

    private void updateTitle() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle((dirty ? "• " : "") + title);
    }

    // ---- load / save -----------------------------------------------------

    private void load() {
        io.execute(() -> {
            String text;
            try {
                InputStream in = new FileInputStream(local);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                in.close();
                text = bos.toString("UTF-8");
            } catch (Exception e) {
                text = "Could not read file: " + e.getMessage();
            }
            final String t = text;
            main.post(() -> {
                loading = true;
                editor.setText(t);
                loading = false;
                dirty = false;
                updateTitle();
            });
        });
    }

    private void save() {
        final byte[] data = editor.getText().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final boolean remote = source != null && source.fs != null && !source.fs.isLocal();
        io.execute(() -> {
            boolean ok = true; String err = null;
            try {
                // Always persist the local working copy.
                FileOutputStream out = new FileOutputStream(local);
                out.write(data);
                out.close();
                // Push back to the originating share when the source is remote.
                if (remote) {
                    OutputStream os = source.fs.write(source.path, data.length);
                    try { os.write(data); } finally { os.close(); }
                }
            } catch (Exception e) { ok = false; err = e.getMessage(); }
            final boolean fok = ok; final String ferr = err;
            main.post(() -> {
                if (fok) { dirty = false; updateTitle(); }
                Toast.makeText(this, fok ? "Saved" : "Save failed: " + ferr, Toast.LENGTH_SHORT).show();
            });
        });
    }

    // ---- actions ---------------------------------------------------------

    private void copyAll() {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText(title, editor.getText().toString()));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    private void setWrap(boolean on) {
        if (wrap == on) return;
        wrap = on;
        int st = editor.getSelectionStart(), en = editor.getSelectionEnd();
        CharSequence t = editor.getText();
        loading = true;
        editor.setHorizontallyScrolling(!wrap);
        editor.setText(t);                       // force the text Layout to rebuild for the new mode
        loading = false;
        int len = editor.length();
        editor.setSelection(Math.min(st, len), Math.min(en, len));
    }

    // ---- find bar --------------------------------------------------------

    private void setupFindBar() {
        findBar = findViewById(R.id.find_bar);
        findInput = findViewById(R.id.find_input);
        ImageButton next = findViewById(R.id.find_next);
        ImageButton prev = findViewById(R.id.find_prev);
        ImageButton close = findViewById(R.id.find_close);
        next.setOnClickListener(v -> findNext(true));
        prev.setOnClickListener(v -> findNext(false));
        close.setOnClickListener(v -> toggleFindBar());
        findInput.setOnEditorActionListener((v, a, e) -> { findNext(true); return true; });
    }

    private void toggleFindBar() {
        boolean show = findBar.getVisibility() != View.VISIBLE;
        findBar.setVisibility(show ? View.VISIBLE : View.GONE);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (show) {
            findInput.requestFocus();
            if (imm != null) imm.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT);
        } else if (imm != null) {
            imm.hideSoftInputFromWindow(findInput.getWindowToken(), 0);
        }
    }

    /** Case-insensitive search from the current cursor; wraps around. forward=false searches back. */
    private void findNext(boolean forward) {
        String q = findInput.getText().toString();
        if (q.isEmpty()) return;
        String hay = editor.getText().toString().toLowerCase(Locale.ROOT);
        String needle = q.toLowerCase(Locale.ROOT);
        int from = Math.max(editor.getSelectionStart(), editor.getSelectionEnd());
        int idx;
        if (forward) {
            idx = hay.indexOf(needle, from);
            if (idx < 0) idx = hay.indexOf(needle); // wrap to top
        } else {
            int before = Math.min(editor.getSelectionStart(), editor.getSelectionEnd()) - 1;
            idx = before >= 0 ? hay.lastIndexOf(needle, before) : -1;
            if (idx < 0) idx = hay.lastIndexOf(needle);   // wrap to bottom
        }
        if (idx < 0) { Toast.makeText(this, "No matches", Toast.LENGTH_SHORT).show(); return; }
        editor.requestFocus();
        editor.setSelection(idx, idx + needle.length());
        scrollToOffset(idx);
    }

    private void scrollToOffset(int offset) {
        editor.post(() -> {
            android.text.Layout layout = editor.getLayout();
            if (layout == null) return;
            int line = layout.getLineForOffset(offset);
            int y = layout.getLineTop(line);
            View parent = (View) editor.getParent();          // HorizontalScrollView
            View scroller = parent != null ? (View) parent.getParent() : null; // ScrollView
            if (scroller != null) scroller.scrollTo(0, Math.max(0, y - editor.getPaddingTop()));
        });
    }

    // ---- exit guard ------------------------------------------------------

    private void maybeFinish() {
        if (!dirty) { finish(); return; }
        new AlertDialog.Builder(this)
                .setTitle("Discard changes?")
                .setMessage("You have unsaved edits to " + title + ".")
                .setPositiveButton("Discard", (d, w) -> finish())
                .setNegativeButton("Keep editing", null)
                .setNeutralButton("Save", (d, w) -> { save(); finish(); })
                .show();
    }

    @Override protected void onDestroy() { super.onDestroy(); io.shutdownNow(); }
}
