package com.sift.explorer;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.sift.explorer.fs.Bookmark;
import com.sift.explorer.fs.FileSystem;
import com.sift.explorer.fs.FileSystemManager;
import com.sift.explorer.fs.RootFileSystem;
import com.sift.explorer.fs.Trash;
import com.sift.explorer.fs.TrashEntry;
import com.sift.explorer.fs.TrashStore;
import com.sift.explorer.ui.BrowserFragment;
import com.sift.explorer.ui.BrowserHost;
import com.sift.explorer.ui.Tab;
import com.sift.explorer.ui.TabManager;
import com.sift.explorer.ui.TabPagerAdapter;
import com.sift.explorer.util.ThemePrefs;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements BrowserHost {

    private ViewPager2 pager;
    private LinearLayout tabStrip;
    private HorizontalScrollView tabScroll;
    private DrawerLayout drawer;
    private LinearLayout bookmarkList;
    private View bookmarkEmpty;
    private TabPagerAdapter adapter;
    private final TabManager tabs = TabManager.get();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean askedPermission = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemePrefs.themeRes(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        drawer = findViewById(R.id.drawer);
        tabScroll = findViewById(R.id.tabScroll);
        tabStrip = findViewById(R.id.tabStrip);
        pager = findViewById(R.id.pager);
        bookmarkList = findViewById(R.id.bookmarkList);
        bookmarkEmpty = findViewById(R.id.bookmarkEmpty);

        setupDrawerControls();

        findViewById(R.id.hamburger).setOnClickListener(v -> drawer.openDrawer(GravityCompat.START));

        if (tabs.isEmpty()) {
            FileSystem internal = FileSystemManager.get(this).internalStorage();
            tabs.add(new Tab(internal, internal.getRootPath()));
        }

        adapter = new TabPagerAdapter(this);
        pager.setAdapter(adapter);
        pager.setOffscreenPageLimit(1);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) { rebuildStrip(); }
        });

        rebuildStrip();
        rebuildDrawer();
    }

    @Override protected void onResume() {
        super.onResume();
        maybeRequestAllFilesAccess();
    }

    private void maybeRequestAllFilesAccess() {
        if (askedPermission) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            askedPermission = true;
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Storage access")
                    .setMessage("Sift needs “All files access” to browse and manage your device storage.")
                    .setNegativeButton("Later", null)
                    .setPositiveButton("Grant", (d, w) -> {
                        try {
                            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception e) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    })
                    .show();
        }
    }

    // ---- appearance controls --------------------------------------------

    private void setupDrawerControls() {
        MaterialSwitch dark = findViewById(R.id.darkSwitch);
        dark.setChecked(ThemePrefs.isDark(this));
        dark.setOnCheckedChangeListener((b, checked) -> {
            if (checked == ThemePrefs.isDark(this)) return;
            ThemePrefs.setDark(this, checked); // triggers recreate via night-mode change
        });

        MaterialButtonToggleGroup accent = findViewById(R.id.accentGroup);
        accent.check(ThemePrefs.GREEN.equals(ThemePrefs.accent(this)) ? R.id.btnAccentGreen : R.id.btnAccentBlue);
        accent.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            chooseAccent(checkedId == R.id.btnAccentGreen ? ThemePrefs.GREEN : ThemePrefs.BLUE);
        });

        MaterialSwitch bin = findViewById(R.id.binSwitch);
        bin.setChecked(ThemePrefs.recycleBin(this));
        bin.setOnCheckedChangeListener((b, checked) -> ThemePrefs.setRecycleBin(this, checked));
        findViewById(R.id.recycleBinRow).setOnClickListener(v -> openRecycleBin());
    }

    // ---- recycle bin -----------------------------------------------------

    private void openRecycleBin() {
        drawer.closeDrawers();
        final TrashStore store = FileSystemManager.get(this).trash();
        final java.util.List<TrashEntry> entries = store.getAll();
        if (entries.isEmpty()) {
            new MaterialAlertDialogBuilder(this).setTitle("Recycle Bin")
                    .setMessage("The Recycle Bin is empty.").setPositiveButton("Close", null).show();
            return;
        }
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            TrashEntry e = entries.get(i);
            CharSequence when = android.text.format.DateUtils.getRelativeTimeSpanString(e.deletedAt);
            labels[i] = e.name + "\n" + when + "  ·  " + e.originalParent;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Recycle Bin (" + entries.size() + ")")
                .setItems(labels, (d, which) -> showTrashOptions(entries.get(which)))
                .setNeutralButton("Empty bin", (d, w) -> confirmEmptyBin(entries))
                .setPositiveButton("Close", null)
                .show();
    }

    private void showTrashOptions(TrashEntry e) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(e.name)
                .setItems(new String[]{"Restore", "Delete forever"}, (d, w) -> {
                    if (w == 0) runTrashOp(e, true);
                    else runTrashOp(e, false);
                })
                .show();
    }

    private void runTrashOp(TrashEntry e, boolean restore) {
        final FileSystemManager fm = FileSystemManager.get(this);
        android.app.ProgressDialog pd = busy(restore ? "Restoring…" : "Deleting…");
        io.execute(() -> {
            String err = null;
            try {
                if (restore) Trash.restore(fm, e, fm.trash());
                else Trash.deleteForever(fm, e, fm.trash());
            } catch (Exception ex) { err = ex.getMessage(); }
            final String ferr = err;
            main.post(() -> {
                pd.dismiss();
                rebuildDrawer();
                refreshCurrentBrowser();
                if (ferr != null) Toast.makeText(this, "Failed: " + ferr, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void confirmEmptyBin(java.util.List<TrashEntry> entries) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Empty Recycle Bin")
                .setMessage("Permanently delete all " + entries.size() + " item(s)? This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Empty", (d, w) -> {
                    final FileSystemManager fm = FileSystemManager.get(this);
                    android.app.ProgressDialog pd = busy("Emptying…");
                    io.execute(() -> {
                        for (TrashEntry e : entries) {
                            try { Trash.deleteForever(fm, e, fm.trash()); } catch (Exception ignore) {}
                        }
                        main.post(() -> { pd.dismiss(); rebuildDrawer(); });
                    });
                })
                .show();
    }

    private void refreshCurrentBrowser() {
        Fragment f = getSupportFragmentManager().findFragmentByTag("f" + adapter.getItemId(pager.getCurrentItem()));
        if (f instanceof BrowserFragment) ((BrowserFragment) f).refresh();
    }

    private void chooseAccent(String accent) {
        if (accent.equals(ThemePrefs.accent(this))) return;
        ThemePrefs.setAccent(this, accent);
        recreate();
    }

    // ---- tab strip -------------------------------------------------------

    public void rebuildStrip() {
        tabStrip.removeAllViews();
        int current = pager.getCurrentItem();
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < tabs.count(); i++) {
            Tab t = tabs.at(i);
            View chip = inf.inflate(R.layout.tab_chip, tabStrip, false);
            TextView title = chip.findViewById(R.id.tabTitle);
            View close = chip.findViewById(R.id.tabClose);
            title.setText(t.title == null || t.title.isEmpty() ? t.fs.getDisplayName() : t.title);
            chip.setActivated(i == current);
            final int pos = i;
            chip.setOnClickListener(v -> pager.setCurrentItem(pos, true));
            close.setOnClickListener(v -> closeTab(t.id));
            tabStrip.addView(chip);
        }
        View plus = inf.inflate(R.layout.tab_new, tabStrip, false);
        plus.setActivated(current >= tabs.count());
        plus.setOnClickListener(v -> pager.setCurrentItem(tabs.count(), true));
        tabStrip.addView(plus);

        tabStrip.post(this::scrollToCurrent);
    }

    private void scrollToCurrent() {
        int current = pager.getCurrentItem();
        if (current < 0 || current >= tabStrip.getChildCount()) return;
        View v = tabStrip.getChildAt(current);
        int target = v.getLeft() - (tabScroll.getWidth() - v.getWidth()) / 2;
        tabScroll.smoothScrollTo(Math.max(0, target), 0);
    }

    // ---- bookmarks drawer ------------------------------------------------

    private void rebuildDrawer() {
        int trashCount = FileSystemManager.get(this).trash().count();
        ((TextView) findViewById(R.id.binCount)).setText(trashCount == 0 ? "Empty" : String.valueOf(trashCount));

        bookmarkList.removeAllViews();
        List<Bookmark> all = FileSystemManager.get(this).bookmarks().getAll();
        bookmarkEmpty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inf = LayoutInflater.from(this);
        for (Bookmark b : all) {
            View row = inf.inflate(R.layout.bookmark_row, bookmarkList, false);
            ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconFor(b.type));
            ((TextView) row.findViewById(R.id.title)).setText(b.label);
            ((TextView) row.findViewById(R.id.subtitle)).setText(b.path);
            row.setOnClickListener(v -> { drawer.closeDrawers(); openBookmark(b); });
            row.findViewById(R.id.remove).setOnClickListener(v -> {
                FileSystemManager.get(this).bookmarks().remove(b.type, b.path, b.connectionId);
                rebuildDrawer();
            });
            bookmarkList.addView(row);
        }
    }

    private int iconFor(String type) {
        switch (type) {
            case "root": return R.drawable.ic_root;
            case "smb":
            case "sftp": return R.drawable.ic_network;
            default: return R.drawable.ic_folder;
        }
    }

    private void openBookmark(Bookmark b) {
        // Local/root resolve instantly; network may connect, so resolve off the UI thread.
        final boolean network = "smb".equals(b.type) || "sftp".equals(b.type);
        final boolean[] cancelled = {false};
        final android.app.ProgressDialog pd;
        if (network) {
            pd = new android.app.ProgressDialog(this);
            pd.setMessage("Connecting…");
            pd.setCancelable(true);
            pd.setCanceledOnTouchOutside(false);
            pd.setButton(android.content.DialogInterface.BUTTON_NEGATIVE, "Cancel", (d, w) -> d.cancel());
            pd.setOnCancelListener(d -> {
                cancelled[0] = true;
                if (b.connectionId != null) FileSystemManager.get(this).evict(b.connectionId);
            });
            pd.show();
        } else {
            pd = null;
        }
        io.execute(() -> {
            try {
                final FileSystem fs = FileSystemManager.get(this).resolveBookmark(b);
                if (network) fs.list(b.path); // surface auth/host errors before opening a tab
                main.post(() -> {
                    if (cancelled[0]) return;
                    if (pd != null) pd.dismiss();
                    openLocation(fs, b.path);
                });
            } catch (final Exception e) {
                main.post(() -> {
                    if (cancelled[0]) return;
                    if (pd != null) pd.dismiss();
                    Toast.makeText(this, "Could not open bookmark: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private android.app.ProgressDialog busy(String msg) {
        android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage(msg);
        pd.setCancelable(false);
        pd.show();
        return pd;
    }

    // ---- BrowserHost -----------------------------------------------------

    @Override public void openLocation(FileSystem fs, String path) {
        int insertAt = tabs.count();
        tabs.add(insertAt, new Tab(fs, path));
        adapter.notifyItemInserted(insertAt);
        // Switch to the new tab only after ViewPager2 has processed the insertion.
        // Doing it synchronously no-ops when we're opening from the phantom home page
        // (whose index equals insertAt), leaving focus stuck on "new tab".
        pager.post(() -> {
            pager.setCurrentItem(insertAt, true);
            rebuildStrip();
        });
    }

    @Override public void onTabUpdated(int tabId) { rebuildStrip(); }

    @Override public void onBookmarksChanged() { rebuildDrawer(); }

    @Override public void closeTab(int tabId) {
        int idx = tabs.indexOf(tabId);
        if (idx < 0) return;
        boolean wasCurrent = pager.getCurrentItem() == idx;
        tabs.remove(idx);
        adapter.notifyItemRemoved(idx);
        if (tabs.isEmpty()) {
            FileSystem internal = FileSystemManager.get(this).internalStorage();
            openLocation(internal, internal.getRootPath());
            return;
        }
        if (wasCurrent) {
            int next = Math.min(idx, tabs.count() - 1);
            pager.setCurrentItem(next, false);
        }
        pager.post(this::rebuildStrip);
    }

    // ---- back navigation -------------------------------------------------

    @Override public void onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) { drawer.closeDrawers(); return; }
        int pos = pager.getCurrentItem();
        Fragment f = getSupportFragmentManager().findFragmentByTag("f" + adapter.getItemId(pos));
        if (f instanceof BrowserFragment && ((BrowserFragment) f).onBackPressed()) {
            return;
        }
        if (pos > 0) { pager.setCurrentItem(pos - 1, true); return; }
        super.onBackPressed();
    }

    @Override protected void onDestroy() { super.onDestroy(); io.shutdownNow(); }
}
