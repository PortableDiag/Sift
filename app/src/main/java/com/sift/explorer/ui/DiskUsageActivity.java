package com.sift.explorer.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.sift.explorer.R;
import com.sift.explorer.fs.FileSystem;
import com.sift.explorer.fs.FileSystemManager;
import com.sift.explorer.util.DiskUsage;
import com.sift.explorer.util.DiskUsage.Node;
import com.sift.explorer.util.ThemePrefs;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen "classic DiskUsage" view: scans a local/root directory tree in the background
 * (cancellable), then renders it as drill-down columns via {@link DiskUsageView}.
 */
public class DiskUsageActivity extends AppCompatActivity implements DiskUsageView.Listener {

    private static final String EXTRA_TYPE = "type", EXTRA_PATH = "path";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private MaterialToolbar toolbar;
    private View progress;
    private TextView progressPath;
    private DiskUsageView view;

    private volatile boolean cancelled;
    private volatile String scanPath = "";
    private long[] usage;   // {used, total} or null

    /** Launches the view for a local ("local") or root ("root") filesystem at {@code path}. */
    public static void open(Context ctx, String fsType, String path) {
        Intent i = new Intent(ctx, DiskUsageActivity.class);
        i.putExtra(EXTRA_TYPE, fsType);
        i.putExtra(EXTRA_PATH, path);
        ctx.startActivity(i);
    }

    @Override protected void onCreate(Bundle s) {
        setTheme(ThemePrefs.themeRes(this));
        super.onCreate(s);
        setContentView(R.layout.activity_disk_usage);

        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        progress = findViewById(R.id.progress);
        progressPath = findViewById(R.id.progressPath);
        findViewById(R.id.progressCancel).setOnClickListener(v -> { cancelled = true; finish(); });

        view = new DiskUsageView(this);
        view.setListener(this);
        ((FrameLayout) findViewById(R.id.usageContainer)).addView(view,
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));

        String type = getIntent().getStringExtra(EXTRA_TYPE);
        String path = getIntent().getStringExtra(EXTRA_PATH);
        startScan(type, path);
    }

    private void startScan(String type, String path) {
        final FileSystem fs;
        try {
            fs = "root".equals(type)
                    ? FileSystemManager.get(this).root()
                    : FileSystemManager.get(this).localFor(path);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open storage: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        final boolean atVolumeRoot = path.equals(fs.getRootPath());
        final String label = atVolumeRoot ? fs.getDisplayName() : fs.nameOf(path);
        usage = atVolumeRoot ? fs.getUsage() : null;

        startProgressTicker();
        io.execute(() -> {
            Node root;
            try {
                root = DiskUsage.scan(fs, path, label, new DiskUsage.Progress() {
                    @Override public void onProgress(long bytesSoFar, String currentPath) { scanPath = currentPath; }
                    @Override public boolean isCancelled() { return cancelled; }
                });
            } catch (DiskUsage.Cancelled c) {
                return;   // activity is finishing
            } catch (final Exception e) {
                main.post(() -> {
                    if (cancelled) return;
                    Toast.makeText(this, "Scan failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    finish();
                });
                return;
            }
            final Node result = root;
            main.post(() -> {
                if (cancelled) return;
                progress.setVisibility(View.GONE);
                if (usage != null && usage[1] > usage[0]) view.setFreeSpace(usage[1] - usage[0]);
                view.setRoot(result);
            });
        });
    }

    /** Coalesces the flood of scan callbacks into a steady on-screen path readout. */
    private void startProgressTicker() {
        main.post(new Runnable() {
            @Override public void run() {
                if (cancelled || progress.getVisibility() != View.VISIBLE) return;
                progressPath.setText(scanPath);
                main.postDelayed(this, 120);
            }
        });
    }

    // ---- DiskUsageView.Listener -----------------------------------------

    @Override public void onNodeOpened(Node node) {
        toolbar.setTitle(node.name);
        StringBuilder sub = new StringBuilder(Formatter.formatFileSize(this, node.size));
        if (usage != null && usage[1] > 0) {
            sub.append("  ·  ").append(Formatter.formatFileSize(this, usage[0]))
               .append(" / ").append(Formatter.formatFileSize(this, usage[1])).append(" used");
        }
        toolbar.setSubtitle(sub.toString());
    }

    @Override public void onFileTapped(Node node) {
        String msg = "Size\n" + Formatter.formatFileSize(this, node.size)
                + "\n\nPath\n" + node.path;
        new MaterialAlertDialogBuilder(this)
                .setTitle(node.name)
                .setMessage(msg)
                .setPositiveButton("Close", null)
                .show();
    }

    @Override public void onBackPressed() {
        if (progress.getVisibility() == View.VISIBLE) { cancelled = true; super.onBackPressed(); return; }
        if (!view.goUp()) super.onBackPressed();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        cancelled = true;
        io.shutdownNow();
    }
}
