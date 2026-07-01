package com.sift.explorer.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.sift.explorer.R;
import com.sift.explorer.fs.Connection;
import com.sift.explorer.fs.FileSystem;
import com.sift.explorer.fs.FileSystemManager;
import com.sift.explorer.fs.LocalFileSystem;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The phantom "new tab" page: quick access to local volumes, root and saved network shares. */
public class HomeFragment extends Fragment {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout container;

    private BrowserHost host() { return (BrowserHost) getActivity(); }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent, @Nullable Bundle s) {
        return inf.inflate(R.layout.fragment_home, parent, false);
    }

    @Override public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        container = v.findViewById(R.id.homeContainer);
        // Insets are handled by the hosting DrawerLayout (fitsSystemWindows).
        build();
    }

    @Override public void onResume() { super.onResume(); build(); }

    private void build() {
        final FileSystemManager fm = FileSystemManager.get(requireContext());
        // Decrypting saved connections and probing volumes touches the keystore/disk —
        // do it off the UI thread, then render.
        io.execute(() -> {
            final List<LocalFileSystem> roots = fm.localRoots();
            final List<Connection> conns = fm.connections().getAll();
            main.post(() -> { if (isAdded() && container != null) render(roots, conns); });
        });
    }

    private void render(List<LocalFileSystem> roots, List<Connection> conns) {
        container.removeAllViews();
        addHeader("Quick access");
        for (LocalFileSystem fs : roots) {
            addItem(fs.getDisplayName().equals("SD card") ? R.drawable.ic_sd : R.drawable.ic_storage,
                    fs.getDisplayName(), fs.getRootPath(), () -> host().openLocation(fs, fs.getRootPath()));
        }
        addItem(R.drawable.ic_root, "Root", "Whole device (/) — requires root", this::openRoot);

        addHeader("Network");
        for (Connection c : conns) addNetworkItem(c);
        addItem(R.drawable.ic_link, "Add connection", "SMB share or SSH/SFTP server", this::addConnection);

        addTipFooter();
    }

    private void openRoot() {
        final FileSystem fs = FileSystemManager.get(requireContext()).root();
        android.app.ProgressDialog pd = busy("Requesting root…");
        io.execute(() -> {
            boolean ok = com.sift.explorer.fs.RootFileSystem.isAvailable();
            main.post(() -> {
                pd.dismiss();
                if (ok) host().openLocation(fs, fs.getRootPath());
                else Toast.makeText(getContext(), "Root access not available on this device", Toast.LENGTH_LONG).show();
            });
        });
    }

    private void addNetworkItem(Connection c) {
        View row = addItem(R.drawable.ic_network, c.displayName(),
                c.type.toUpperCase() + "  ·  " + c.host, () -> openConnection(c));
        row.setOnLongClickListener(v -> { editOrDelete(c); return true; });
    }

    private void openConnection(Connection c) {
        final boolean[] cancelled = {false};
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(requireContext());
        pd.setMessage("Connecting to " + c.host + "…");
        pd.setCancelable(true);
        pd.setCanceledOnTouchOutside(false);
        pd.setButton(android.content.DialogInterface.BUTTON_NEGATIVE, "Cancel", (d, w) -> d.cancel());
        pd.setOnCancelListener(d -> {
            cancelled[0] = true;
            // closing the (cached) session unblocks the pending socket connect
            FileSystemManager.get(requireContext()).evict(c.id);
        });
        pd.show();
        io.execute(() -> {
            try {
                final FileSystem fs = FileSystemManager.get(requireContext()).forConnection(c);
                fs.list(fs.getRootPath()); // surface auth/host errors before opening a tab
                main.post(() -> {
                    if (cancelled[0]) return;
                    pd.dismiss();
                    host().openLocation(fs, fs.getRootPath());
                });
            } catch (final Exception e) {
                main.post(() -> {
                    if (cancelled[0]) return;
                    pd.dismiss();
                    FileSystemManager.get(requireContext()).evict(c.id);
                    Toast.makeText(getContext(), "Connection failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void addConnection() {
        ConnectionEditorDialog.show(requireContext(), null, FileSystemManager.get(requireContext()).connections(), this::build);
    }

    private void editOrDelete(Connection c) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(c.displayName())
                .setItems(new String[]{"Edit", "Delete"}, (d, w) -> {
                    if (w == 0) ConnectionEditorDialog.show(requireContext(), c,
                            FileSystemManager.get(requireContext()).connections(), this::build);
                    else {
                        FileSystemManager.get(requireContext()).connections().delete(c.id);
                        FileSystemManager.get(requireContext()).evict(c.id);
                        build();
                    }
                })
                .show();
    }

    // ---- view building ---------------------------------------------------

    private void addHeader(String text) {
        TextView tv = new TextView(requireContext());
        tv.setText(text);
        tv.setTextSize(13);
        tv.setAllCaps(true);
        tv.setLetterSpacing(0.06f);
        tv.setTextColor(com.sift.explorer.util.ThemePrefs.themeColor(requireContext(), com.google.android.material.R.attr.colorOnSurfaceVariant));
        tv.setPadding(dp(20), dp(20), dp(20), dp(8));
        container.addView(tv);
    }

    private View addItem(int icon, String title, String subtitle, Runnable onClick) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.home_item, container, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView sub = row.findViewById(R.id.subtitle);
        if (subtitle == null || subtitle.isEmpty()) sub.setVisibility(View.GONE);
        else sub.setText(subtitle);
        row.setOnClickListener(v -> onClick.run());
        container.addView(row);
        return row;
    }

    private void addTipFooter() {
        TextView tv = new TextView(requireContext());
        tv.setText("Tip: swipe right past the last tab to open another. Swipe between tabs to compare two locations side by side.");
        tv.setTextSize(12);
        tv.setTextColor(com.sift.explorer.util.ThemePrefs.themeColor(requireContext(), com.google.android.material.R.attr.colorOnSurfaceVariant));
        tv.setPadding(dp(20), dp(28), dp(20), dp(28));
        container.addView(tv);
    }

    private android.app.ProgressDialog busy(String msg) {
        android.app.ProgressDialog pd = new android.app.ProgressDialog(requireContext());
        pd.setMessage(msg);
        pd.setCancelable(false);
        pd.show();
        return pd;
    }

    private int dp(int d) { return Math.round(getResources().getDisplayMetrics().density * d); }

    @Override public void onDestroy() { super.onDestroy(); io.shutdownNow(); }
}
