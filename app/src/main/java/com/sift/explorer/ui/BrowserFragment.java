package com.sift.explorer.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.sift.explorer.R;
import com.sift.explorer.fs.Clipboard;
import com.sift.explorer.fs.FileItem;
import com.sift.explorer.fs.FileOps;
import com.sift.explorer.fs.FileSystem;
import com.sift.explorer.fs.LocalFileSystem;
import com.sift.explorer.util.MimeUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One browser tab: lists a directory, navigates, and runs file operations. */
public class BrowserFragment extends Fragment implements FileAdapter.Listener {

    private static final String ARG_TAB = "tab";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private Tab tab;
    private MaterialToolbar toolbar;
    private RecyclerView recycler;
    private FileAdapter adapter;
    private SwipeRefreshLayout swipe;
    private View progress;
    private View emptyView;
    private LinearLayout crumbBar;
    private android.widget.HorizontalScrollView crumbScroll;
    private View selectionBar;
    private FloatingActionButton fab;

    private final List<FileItem> fullList = new ArrayList<>();
    private String filter = "";
    private com.sift.explorer.fs.BookmarkStore bookmarks;

    public static BrowserFragment newInstance(int tabId) {
        BrowserFragment f = new BrowserFragment();
        Bundle b = new Bundle();
        b.putInt(ARG_TAB, tabId);
        f.setArguments(b);
        return f;
    }

    private BrowserHost host() { return (BrowserHost) getActivity(); }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent, @Nullable Bundle s) {
        return inf.inflate(R.layout.fragment_browser, parent, false);
    }

    @Override public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        int tabId = getArguments() != null ? getArguments().getInt(ARG_TAB) : -1;
        tab = TabManager.get().byId(tabId);

        toolbar = v.findViewById(R.id.toolbar);
        recycler = v.findViewById(R.id.recycler);
        swipe = v.findViewById(R.id.swipe);
        progress = v.findViewById(R.id.progress);
        emptyView = v.findViewById(R.id.emptyView);
        crumbBar = v.findViewById(R.id.crumbBar);
        crumbScroll = v.findViewById(R.id.crumbScroll);
        selectionBar = v.findViewById(R.id.selectionBar);
        fab = v.findViewById(R.id.fab);

        if (tab == null) { // state lost (process death); nothing to show
            toolbar.setTitle("Sift");
            return;
        }

        bookmarks = com.sift.explorer.fs.FileSystemManager.get(requireContext()).bookmarks();
        adapter = new FileAdapter(requireContext(), this);
        applyLayoutManager();
        recycler.setAdapter(adapter);

        // The hosting DrawerLayout (fitsSystemWindows) already lifts content above the
        // navigation bar; just reserve space so the FAB / selection bar clear the last row.
        recycler.setPadding(recycler.getPaddingLeft(), recycler.getPaddingTop(),
                recycler.getPaddingRight(), dp(88));

        toolbar.setNavigationOnClickListener(x -> { if (!onBackPressed()) host().closeTab(tab.id); });
        setupMenu();
        swipe.setOnRefreshListener(this::refresh);

        v.findViewById(R.id.btnCopy).setOnClickListener(x -> clipboardSelection(Clipboard.Mode.COPY));
        v.findViewById(R.id.btnCut).setOnClickListener(x -> clipboardSelection(Clipboard.Mode.MOVE));
        v.findViewById(R.id.btnDelete).setOnClickListener(x -> deleteSelection());
        v.findViewById(R.id.btnShare).setOnClickListener(x -> shareSelection());
        v.findViewById(R.id.btnMore).setOnClickListener(this::showSelectionMore);

        fab.setOnClickListener(x -> {
            if (!Clipboard.isEmpty()) pasteHere();
            else promptNewFolder();
        });

        load(tab.path, false);
    }

    private void applyLayoutManager() {
        if (tab.grid) {
            recycler.setLayoutManager(new GridLayoutManager(requireContext(), spanCount()));
        } else {
            recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        }
    }

    private int spanCount() {
        float w = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        return Math.max(3, (int) (w / 110));
    }

    // ---- menu ------------------------------------------------------------

    private void setupMenu() {
        toolbar.getMenu().clear();
        toolbar.inflateMenu(R.menu.browser_menu);
        View actionView = toolbar.getMenu().findItem(R.id.action_search).getActionView();
        if (actionView instanceof androidx.appcompat.widget.SearchView) {
            androidx.appcompat.widget.SearchView search = (androidx.appcompat.widget.SearchView) actionView;
            search.setQueryHint("Search this folder");
            search.setOnQueryTextListener(new androidx.appcompat.widget.SearchView.OnQueryTextListener() {
                @Override public boolean onQueryTextSubmit(String q) { return false; }
                @Override public boolean onQueryTextChange(String q) { filter = q == null ? "" : q.trim(); applyFilterAndSort(); return true; }
            });
            toolbar.getMenu().findItem(R.id.action_search).setOnActionExpandListener(
                new android.view.MenuItem.OnActionExpandListener() {
                    @Override public boolean onMenuItemActionExpand(android.view.MenuItem i) { return true; }
                    @Override public boolean onMenuItemActionCollapse(android.view.MenuItem i) {
                        filter = ""; applyFilterAndSort(); return true;
                    }
                });
        }
        toolbar.getMenu().findItem(R.id.action_view_toggle)
                .setIcon(tab.grid ? R.drawable.ic_list : R.drawable.ic_grid);
        toolbar.setOnMenuItemClickListener(this::onMenu);
    }

    private boolean onMenu(android.view.MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_view_toggle) {
            tab.grid = !tab.grid;
            applyLayoutManager();
            adapter.setGrid(tab.grid);
            item.setIcon(tab.grid ? R.drawable.ic_list : R.drawable.ic_grid);
            return true;
        } else if (id == R.id.action_sort) { showSortDialog(); return true; }
        else if (id == R.id.action_new_folder) { promptNewFolder(); return true; }
        else if (id == R.id.action_new_file) { promptNewFile(); return true; }
        else if (id == R.id.action_select_all) { enterSelection(); adapter.selectAll(); updateSelectionBar(); return true; }
        else if (id == R.id.action_paste) { pasteHere(); return true; }
        else if (id == R.id.action_refresh) { refresh(); return true; }
        else if (id == R.id.action_show_hidden) {
            tab.showHidden = !tab.showHidden;
            item.setChecked(tab.showHidden);
            applyFilterAndSort();
            return true;
        } else if (id == R.id.action_properties) { showProperties(Collections.singletonList(currentDirItem())); return true; }
        else if (id == R.id.action_bookmark) { toggleBookmark(); return true; }
        return false;
    }

    private boolean isBookmarked() {
        return bookmarks != null && bookmarks.contains(tab.fs.getType(), tab.path, tab.fs.connectionId());
    }

    private void toggleBookmark() {
        boolean had = isBookmarked();
        if (had) {
            bookmarks.remove(tab.fs.getType(), tab.path, tab.fs.connectionId());
        } else {
            String label = (tab.title == null || tab.title.isEmpty()) ? tab.fs.getDisplayName() : tab.title;
            bookmarks.add(new com.sift.explorer.fs.Bookmark(label, tab.fs.getType(), tab.path, tab.fs.connectionId()));
        }
        if (host() != null) host().onBookmarksChanged();
        updateBookmarkMenuTitle();
        toast(had ? "Bookmark removed" : "Bookmarked");
    }

    private void updateBookmarkMenuTitle() {
        android.view.MenuItem item = toolbar.getMenu().findItem(R.id.action_bookmark);
        if (item != null) item.setTitle(isBookmarked() ? "Remove bookmark" : "Bookmark folder");
    }

    // ---- loading & navigation -------------------------------------------

    public void open(FileItem dir) {
        tab.back.push(tab.path);
        load(dir.path, false);
    }

    private void load(String path, boolean isBack) {
        if (tab == null) return;
        tab.path = path;
        tab.title = tab.fs.isRoot(path) ? tab.fs.getDisplayName() : tab.fs.nameOf(path);
        if (tab.title == null || tab.title.isEmpty()) tab.title = tab.fs.getDisplayName();
        exitSelection();
        showProgress(true);
        updateChrome();
        if (host() != null) host().onTabUpdated(tab.id);
        final FileSystem fs = tab.fs;
        final String p = path;
        io.execute(() -> {
            try {
                final List<FileItem> result = fs.list(p);
                main.post(() -> {
                    if (!isAdded() || !p.equals(tab.path)) return;
                    fullList.clear();
                    fullList.addAll(result);
                    showProgress(false);
                    applyFilterAndSort();
                });
            } catch (final Exception e) {
                main.post(() -> {
                    if (!isAdded() || !p.equals(tab.path)) return;
                    showProgress(false);
                    fullList.clear();
                    applyFilterAndSort();
                    showError(e.getMessage());
                });
            }
        });
    }

    public void refresh() { load(tab.path, false); }

    public boolean onBackPressed() {
        if (adapter != null && adapter.isSelectionMode()) { exitSelection(); return true; }
        if (tab == null) return false;
        if (!tab.back.isEmpty()) { load(tab.back.pop(), true); return true; }
        if (!tab.fs.isRoot(tab.path)) {
            String parent = tab.fs.getParent(tab.path);
            if (parent != null) { load(parent, true); return true; }
        }
        return false;
    }

    private final List<FileItem> shownList = new ArrayList<>();

    private void applyFilterAndSort() {
        if (adapter == null) return;
        shownList.clear();
        for (FileItem f : fullList) {
            if (!tab.showHidden && f.isHidden()) continue;
            if (!filter.isEmpty() && !f.name.toLowerCase().contains(filter.toLowerCase())) continue;
            shownList.add(f);
        }
        sort(shownList);
        adapter.setItems(shownList, tab.grid);
        emptyView.setVisibility(shownList.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void sort(List<FileItem> list) {
        Comparator<FileItem> c;
        switch (tab.sort) {
            case Tab.SORT_SIZE: c = (a, b) -> Long.compare(a.size, b.size); break;
            case Tab.SORT_DATE: c = (a, b) -> Long.compare(a.lastModified, b.lastModified); break;
            case Tab.SORT_TYPE: c = (a, b) -> a.getExtension().compareToIgnoreCase(b.getExtension()); break;
            default: c = (a, b) -> a.name.compareToIgnoreCase(b.name);
        }
        if (tab.sortDesc) c = c.reversed();
        final Comparator<FileItem> byField = c;
        Comparator<FileItem> full = (a, b) -> {
            if (tab.foldersFirst && a.isDirectory != b.isDirectory) return a.isDirectory ? -1 : 1;
            int r = byField.compare(a, b);
            return r != 0 ? r : a.name.compareToIgnoreCase(b.name);
        };
        Collections.sort(list, full);
    }

    // ---- item interaction ------------------------------------------------

    @Override public void onItemClick(FileItem item, int position) {
        if (adapter.isSelectionMode()) { adapter.toggle(item); updateSelectionBar(); return; }
        if (item.isDirectory) open(item);
        else openFile(item);
    }

    @Override public void onItemLongClick(FileItem item, int position) {
        if (!adapter.isSelectionMode()) enterSelection();
        adapter.toggle(item);
        updateSelectionBar();
    }

    /** Per-item context menu (the ⋮ on each row). */
    @Override public void onItemMenu(FileItem item, View anchor, int position) {
        final List<FileItem> one = Collections.singletonList(item);
        android.widget.PopupMenu pm = new android.widget.PopupMenu(requireContext(), anchor);
        pm.getMenu().add("Open");
        if (!item.isDirectory) pm.getMenu().add("Open with…");
        pm.getMenu().add("Copy");
        pm.getMenu().add("Cut");
        pm.getMenu().add("Rename");
        pm.getMenu().add("Delete");
        if (!item.isDirectory) pm.getMenu().add("Share");
        pm.getMenu().add("Compress to ZIP");
        if (!item.isDirectory && item.getExtension().equals("zip")) pm.getMenu().add("Extract here");
        if (item.isDirectory) {
            boolean marked = bookmarks != null
                    && bookmarks.contains(tab.fs.getType(), item.path, tab.fs.connectionId());
            pm.getMenu().add(marked ? "Remove bookmark" : "Bookmark");
        }
        pm.getMenu().add("Copy path");
        pm.getMenu().add("Properties");
        pm.setOnMenuItemClickListener(mi -> {
            switch (mi.getTitle().toString()) {
                case "Open": if (item.isDirectory) open(item); else openFile(item); break;
                case "Open with…": openExternal(item, true); break;
                case "Copy": clipboardItems(one, Clipboard.Mode.COPY); break;
                case "Cut": clipboardItems(one, Clipboard.Mode.MOVE); break;
                case "Rename": promptRename(item); break;
                case "Delete": deleteItems(one); break;
                case "Share": shareItems(one); break;
                case "Compress to ZIP": promptCompress(one); break;
                case "Extract here": extract(item); break;
                case "Bookmark": case "Remove bookmark": bookmarkItem(item); break;
                case "Copy path": copyPathItem(item); break;
                case "Properties": PropertiesDialog.show(requireContext(), one, io, main); break;
            }
            return true;
        });
        pm.show();
    }

    private void bookmarkItem(FileItem item) {
        if (bookmarks == null || !item.isDirectory) return;
        String type = tab.fs.getType(), conn = tab.fs.connectionId();
        if (bookmarks.contains(type, item.path, conn)) {
            bookmarks.remove(type, item.path, conn);
            toast("Bookmark removed");
        } else {
            bookmarks.add(new com.sift.explorer.fs.Bookmark(item.name, type, item.path, conn));
            toast("Bookmarked");
        }
        if (host() != null) host().onBookmarksChanged();
    }

    private void copyPathItem(FileItem item) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("path", item.path));
        toast("Path copied");
    }

    private void openFile(FileItem item) {
        if (MimeUtils.isImage(item)) {
            openImageGallery(item);
        } else if (MimeUtils.isTextLike(item) && item.size < 5_000_000) {
            withLocalCopy(item, file -> TextViewerActivity.open(requireContext(), file.getAbsolutePath(), item.name,
                    item.fs.isLocal()));
        } else {
            openExternal(item, false);
        }
    }

    /** Launch the swipeable gallery over all images in the current folder view. */
    private void openImageGallery(FileItem item) {
        List<FileItem> imgs = new ArrayList<>();
        for (FileItem f : shownList) if (MimeUtils.isImage(f)) imgs.add(f);
        int idx = imgs.indexOf(item);
        if (idx < 0) { imgs = Collections.singletonList(item); idx = 0; }
        ImageGallery.set(imgs, idx);
        ImageViewerActivity.open(requireContext());
    }

    /** Open a non-previewable file with the remembered app, or the picker. */
    private void openExternal(FileItem item, boolean forcePicker) {
        final String mime = MimeUtils.mimeType(item);
        final String typeKey = MimeUtils.categoryOf(item).name();
        final String typeLabel = typeLabel(item);
        final com.sift.explorer.util.DefaultApps defaults = new com.sift.explorer.util.DefaultApps(requireContext());
        withLocalCopy(item, file -> {
            if (forcePicker) OpenWith.picker(requireActivity(), file, mime, typeKey, typeLabel, defaults);
            else OpenWith.open(requireActivity(), file, mime, typeKey, typeLabel, defaults);
        });
    }

    private String typeLabel(FileItem item) {
        switch (MimeUtils.categoryOf(item)) {
            case VIDEO: return "video files";
            case AUDIO: return "audio files";
            case PDF: return "PDF files";
            case DOC: return "documents";
            case ARCHIVE: return "archives";
            case IMAGE: return "images";
            default: {
                String e = item.getExtension();
                return e.isEmpty() ? "these files" : "." + e + " files";
            }
        }
    }

    interface FileCb { void run(File f); }

    /** Provides a local File for an item, downloading remote files to cache first. */
    private void withLocalCopy(FileItem item, FileCb cb) {
        File local = item.asLocalFile();
        if (local != null) { cb.run(local); return; }
        final File cache = new File(requireContext().getCacheDir(), "open/" + item.name);
        cache.getParentFile().mkdirs();
        cache.delete(); // avoid serving a stale copy
        android.app.ProgressDialog pd = showBusy("Opening " + item.name + "…");
        LocalFileSystem cacheFs = new LocalFileSystem(requireContext().getCacheDir().getAbsolutePath(), "cache");
        List<FileItem> one = Collections.singletonList(item);
        FileOps.copy(one, cacheFs, cache.getParentFile().getAbsolutePath(), false, new FileOps.Listener() {
            @Override public void onProgress(FileOps.Progress p) {}
            @Override public void onDone(boolean ok, String err) {
                pd.dismiss();
                if (ok && cache.exists()) cb.run(cache);
                else showError(err == null ? "Could not open file" : err);
            }
        });
    }

    // ---- selection -------------------------------------------------------

    private void enterSelection() {
        adapter.setSelectionMode(true);
        selectionBar.setVisibility(View.VISIBLE);
        fab.hide();
        // NB: do not call updateSelectionBar() here — nothing is selected yet, and it
        // would immediately exit selection mode. Callers toggle first, then update.
    }

    private void exitSelection() {
        if (adapter != null) adapter.setSelectionMode(false);
        if (selectionBar != null) selectionBar.setVisibility(View.GONE);
        if (fab != null) updateFab();
        updateChrome();
    }

    private void updateSelectionBar() {
        int n = adapter.selectedCount();
        if (n == 0) { exitSelection(); return; }
        toolbar.setTitle(n + " selected");
    }

    private void clipboardSelection(Clipboard.Mode mode) {
        clipboardItems(adapter.getSelected(), mode);
    }

    private void clipboardItems(List<FileItem> items, Clipboard.Mode mode) {
        if (items.isEmpty()) return;
        Clipboard.set(items, mode);
        exitSelection();
        toast((mode == Clipboard.Mode.COPY ? "Copied " : "Cut ") + items.size() + " item(s) — tap paste");
        updateFab();
    }

    private void pasteHere() {
        if (Clipboard.isEmpty()) return;
        final boolean move = Clipboard.mode() == Clipboard.Mode.MOVE;
        List<FileItem> items = Clipboard.items();
        final android.app.ProgressDialog pd = showProgressDialog(move ? "Moving…" : "Copying…");
        FileOps.Task task = FileOps.copy(items, tab.fs, tab.path, move, opListener(pd, () -> {
            if (move) Clipboard.clear();
            updateFab();
            refresh();
        }));
        pd.setOnCancelListener(d -> task.cancel());
    }

    private void deleteSelection() { deleteItems(adapter.getSelected()); }

    private void deleteItems(final List<FileItem> sel) {
        if (sel.isEmpty()) return;
        if (com.sift.explorer.util.ThemePrefs.recycleBin(requireContext())) {
            moveToRecycleBin(sel);
            return;
        }
        String what = sel.size() == 1 ? "“" + sel.get(0).name + "”" : sel.size() + " items";
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete")
                .setMessage("Delete " + what + "? This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    android.app.ProgressDialog pd = showProgressDialog("Deleting…");
                    FileOps.delete(sel, opListener(pd, this::refresh));
                })
                .show();
    }

    private void moveToRecycleBin(final List<FileItem> sel) {
        final com.sift.explorer.fs.TrashStore store =
                com.sift.explorer.fs.FileSystemManager.get(requireContext()).trash();
        final android.app.ProgressDialog pd = showProgressDialog("Moving to Recycle Bin…");
        io.execute(() -> {
            final List<com.sift.explorer.fs.TrashEntry> done = new ArrayList<>();
            String err = null;
            for (FileItem it : sel) {
                try { done.add(com.sift.explorer.fs.Trash.moveToTrash(it.fs, it, store)); }
                catch (Exception e) { err = e.getMessage(); }
            }
            final String ferr = err;
            main.post(() -> {
                pd.dismiss();
                exitSelection();
                refresh();
                if (host() != null) host().onBookmarksChanged();
                if (!done.isEmpty()) showUndoBin(done);
                if (ferr != null) toast("Some items couldn't be binned: " + ferr);
            });
        });
    }

    private void showUndoBin(final List<com.sift.explorer.fs.TrashEntry> entries) {
        com.google.android.material.snackbar.Snackbar
                .make(requireView(), "Moved " + entries.size() + " to Recycle Bin",
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAction("UNDO", v -> undoBin(entries))
                .show();
    }

    private void undoBin(final List<com.sift.explorer.fs.TrashEntry> entries) {
        final com.sift.explorer.fs.FileSystemManager fm =
                com.sift.explorer.fs.FileSystemManager.get(requireContext());
        final android.app.ProgressDialog pd = showBusy("Restoring…");
        io.execute(() -> {
            for (com.sift.explorer.fs.TrashEntry e : entries) {
                try { com.sift.explorer.fs.Trash.restore(fm, e, fm.trash()); } catch (Exception ignore) {}
            }
            main.post(() -> { pd.dismiss(); refresh(); if (host() != null) host().onBookmarksChanged(); });
        });
    }

    private void shareSelection() { shareItems(adapter.getSelected()); }

    private void shareItems(final List<FileItem> sel) {
        if (sel.isEmpty()) return;
        // Only local files can be shared via FileProvider; download remotes to cache.
        io.execute(() -> {
            ArrayList<Uri> uris = new ArrayList<>();
            for (FileItem item : sel) {
                if (item.isDirectory) continue;
                try {
                    File f = item.asLocalFile();
                    if (f == null) {
                        f = new File(requireContext().getCacheDir(), "share/" + item.name);
                        f.getParentFile().mkdirs();
                        copyStream(item, f);
                    }
                    uris.add(FileProvider.getUriForFile(requireContext(),
                            requireContext().getPackageName() + ".fileprovider", f));
                } catch (Exception ignore) {}
            }
            main.post(() -> {
                if (uris.isEmpty()) { showError("Nothing to share"); return; }
                Intent i = new Intent(uris.size() > 1 ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND);
                i.setType("*/*");
                if (uris.size() > 1) i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                else i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Share"));
                exitSelection();
            });
        });
    }

    private void copyStream(FileItem item, File dest) throws Exception {
        java.io.InputStream in = item.fs.read(item);
        java.io.OutputStream out = new java.io.FileOutputStream(dest);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally { in.close(); out.close(); }
    }

    private void showSelectionMore(View anchor) {
        final List<FileItem> sel = adapter.getSelected();
        if (sel.isEmpty()) return;
        android.widget.PopupMenu pm = new android.widget.PopupMenu(requireContext(), anchor);
        pm.getMenu().add("Rename").setEnabled(sel.size() == 1);
        pm.getMenu().add("Compress to ZIP");
        if (sel.size() == 1 && sel.get(0).getExtension().equals("zip")) pm.getMenu().add("Extract here");
        pm.getMenu().add("Copy path").setEnabled(sel.size() == 1);
        pm.getMenu().add("Properties");
        pm.setOnMenuItemClickListener(mi -> {
            String t = mi.getTitle().toString();
            switch (t) {
                case "Rename": promptRename(sel.get(0)); break;
                case "Compress to ZIP": promptCompress(sel); break;
                case "Extract here": extract(sel.get(0)); break;
                case "Copy path": copyPath(sel.get(0)); break;
                case "Properties": showProperties(sel); break;
            }
            return true;
        });
        pm.show();
    }

    // ---- operations: create / rename / zip / properties ------------------

    private void promptNewFolder() { promptName("New folder", "Folder name", "", name -> {
        runIo(() -> tab.fs.mkdirs(tab.fs.childPath(tab.path, name)), this::refresh, "Could not create folder");
    }); }

    private void promptNewFile() { promptName("New file", "File name", "", name -> {
        runIo(() -> tab.fs.createFile(tab.fs.childPath(tab.path, name)), this::refresh, "Could not create file");
    }); }

    private void promptRename(FileItem item) { promptName("Rename", "New name", item.name, name -> {
        runIo(() -> tab.fs.rename(item, name), this::refresh, "Rename failed");
    }); }

    private void promptCompress(List<FileItem> items) {
        String def = (items.size() == 1 ? items.get(0).name : tab.fs.nameOf(tab.path)) + ".zip";
        promptName("Compress to ZIP", "Archive name", def, name -> {
            android.app.ProgressDialog pd = showProgressDialog("Compressing…");
            FileOps.Task t = FileOps.compress(items, tab.fs, tab.fs.childPath(tab.path, name), opListener(pd, this::refresh));
            pd.setOnCancelListener(d -> t.cancel());
        });
    }

    private void extract(FileItem zip) {
        android.app.ProgressDialog pd = showProgressDialog("Extracting…");
        FileOps.Task t = FileOps.extract(zip, tab.fs, tab.path, opListener(pd, this::refresh));
        pd.setOnCancelListener(d -> t.cancel());
    }

    private void copyPath(FileItem item) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("path", item.path));
        toast("Path copied");
        exitSelection();
    }

    private void showProperties(List<FileItem> items) {
        if (items.isEmpty() || items.get(0) == null) return;
        PropertiesDialog.show(requireContext(), items, io, main);
        exitSelection();
    }

    private FileItem currentDirItem() {
        // Build directly (no stat) so this never blocks the UI thread on remote/root tabs;
        // PropertiesDialog computes size/contents in the background anyway.
        return new FileItem(tab.fs, tab.path, tab.fs.nameOf(tab.path), true, false, 0, 0, null);
    }

    // ---- dialogs ---------------------------------------------------------

    interface NameCb { void run(String name); }

    private void promptName(String title, String hint, String preset, NameCb cb) {
        final android.widget.EditText et = new android.widget.EditText(requireContext());
        et.setHint(hint);
        et.setText(preset);
        et.setSelection(0, preset.contains(".") ? preset.lastIndexOf('.') : preset.length());
        int pad = dp(20);
        FrameLayoutPad container = new FrameLayoutPad(requireContext(), et, pad);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setView(container)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("OK", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (!name.isEmpty()) cb.run(name);
                })
                .show();
    }

    private void showSortDialog() {
        String[] opts = {"Name", "Size", "Date", "Type"};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Sort by")
                .setSingleChoiceItems(opts, tab.sort, (d, which) -> tab.sort = which)
                .setNeutralButton(tab.sortDesc ? "Ascending" : "Descending", (d, w) -> {
                    tab.sortDesc = !tab.sortDesc; rememberSort(); applyFilterAndSort();
                })
                .setPositiveButton("Apply", (d, w) -> { rememberSort(); applyFilterAndSort(); })
                .show();
    }

    /** Persist the tab's sort as the last-set choice so new tabs and future launches use it. */
    private void rememberSort() {
        Tab.defaultSort = tab.sort;
        Tab.defaultSortDesc = tab.sortDesc;
        com.sift.explorer.util.ThemePrefs.setSort(requireContext(), tab.sort, tab.sortDesc);
    }

    // ---- helpers ---------------------------------------------------------

    private interface IoAction { void run() throws Exception; }

    private void runIo(IoAction action, Runnable onOk, String errMsg) {
        io.execute(() -> {
            try { action.run(); main.post(onOk); }
            catch (Exception e) { main.post(() -> showError(errMsg + ": " + e.getMessage())); }
        });
    }

    private FileOps.Listener opListener(android.app.ProgressDialog pd, Runnable onOk) {
        return new FileOps.Listener() {
            @Override public void onProgress(FileOps.Progress p) {
                if (p.totalBytes > 0) {
                    pd.setIndeterminate(false);
                    pd.setMax(100);
                    pd.setProgress((int) (100 * p.doneBytes / Math.max(1, p.totalBytes)));
                }
                pd.setMessage(p.name);
            }
            @Override public void onDone(boolean ok, String err) {
                pd.dismiss();
                if (!ok && err != null && !err.equals("Cancelled")) showError(err);
                onOk.run();
            }
        };
    }

    private android.app.ProgressDialog showProgressDialog(String msg) {
        android.app.ProgressDialog pd = new android.app.ProgressDialog(requireContext());
        pd.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        pd.setTitle(msg);
        pd.setIndeterminate(true);
        pd.setCancelable(true);
        pd.show();
        return pd;
    }

    private android.app.ProgressDialog showBusy(String msg) {
        android.app.ProgressDialog pd = new android.app.ProgressDialog(requireContext());
        pd.setMessage(msg);
        pd.setCancelable(false);
        pd.show();
        return pd;
    }

    private void updateChrome() {
        if (tab == null) return;
        toolbar.setTitle(tab.title);
        buildBreadcrumb();
        updateFab();
        updateBookmarkMenuTitle();
    }

    private void updateFab() {
        if (fab == null) return;
        if (!Clipboard.isEmpty()) {
            fab.setImageResource(R.drawable.ic_paste);
            fab.show();
        } else {
            fab.setImageResource(R.drawable.ic_newfolder);
            fab.show();
        }
    }

    private void buildBreadcrumb() {
        crumbBar.removeAllViews();
        List<String[]> segs = new ArrayList<>(); // {label, path}
        String root = tab.fs.getRootPath();
        segs.add(new String[]{tab.fs.getDisplayName(), root});
        if (tab.path != null && !tab.fs.isRoot(tab.path)) {
            String rel = tab.path;
            if (rel.startsWith(root)) rel = rel.substring(root.length());
            String acc = root;
            for (String part : rel.split("/")) {
                if (part.isEmpty()) continue;
                acc = tab.fs.childPath(acc, part);
                segs.add(new String[]{part, acc});
            }
        }
        for (int i = 0; i < segs.size(); i++) {
            final String[] seg = segs.get(i);
            TextView tv = new TextView(requireContext());
            tv.setText(seg[0]);
            tv.setTextSize(13);
            tv.setPadding(dp(8), dp(6), dp(8), dp(6));
            tv.setMaxLines(1);
            boolean last = i == segs.size() - 1;
            tv.setTextColor(com.sift.explorer.util.ThemePrefs.themeColor(requireContext(),
                    last ? com.google.android.material.R.attr.colorPrimary
                         : com.google.android.material.R.attr.colorOnSurfaceVariant));
            tv.setOnClickListener(x -> { if (!seg[1].equals(tab.path)) { tab.back.push(tab.path); load(seg[1], false); } });
            crumbBar.addView(tv);
            if (!last) {
                TextView sep = new TextView(requireContext());
                sep.setText("›");
                sep.setTextColor(com.sift.explorer.util.ThemePrefs.themeColor(requireContext(),
                        com.google.android.material.R.attr.colorOnSurfaceVariant));
                sep.setPadding(0, dp(6), 0, dp(6));
                crumbBar.addView(sep);
            }
        }
        crumbScroll.post(() -> crumbScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private void showProgress(boolean show) {
        progress.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) swipe.setRefreshing(false);
    }

    private void showError(String msg) {
        emptyView.setVisibility(fullList.isEmpty() ? View.VISIBLE : View.GONE);
        toast(msg == null ? "Error" : msg);
    }

    private void toast(String m) { Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show(); }

    private int dp(int d) { return Math.round(getResources().getDisplayMetrics().density * d); }

    @Override public void onResume() { super.onResume(); updateFab(); }

    @Override public void onDestroy() { super.onDestroy(); io.shutdownNow(); }

    /** Small helper: wrap a view with uniform padding for dialogs. */
    static class FrameLayoutPad extends android.widget.FrameLayout {
        FrameLayoutPad(android.content.Context c, View child, int pad) {
            super(c);
            setPadding(pad, pad / 2, pad, 0);
            addView(child);
        }
    }
}
