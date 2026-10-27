package com.sift.explorer.api;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.FileProvider;

import com.sift.explorer.MainActivity;
import com.sift.explorer.fs.Bookmark;
import com.sift.explorer.fs.Connection;
import com.sift.explorer.fs.FileItem;
import com.sift.explorer.fs.FileOps;
import com.sift.explorer.fs.FileSystem;
import com.sift.explorer.fs.FileSystemManager;
import com.sift.explorer.fs.LocalFileSystem;
import com.sift.explorer.fs.RootFileSystem;
import com.sift.explorer.fs.Trash;
import com.sift.explorer.fs.TrashEntry;
import com.sift.explorer.ui.FloatingImageService;
import com.sift.explorer.ui.ImageGallery;
import com.sift.explorer.ui.ImageViewerActivity;
import com.sift.explorer.ui.OpenWith;
import com.sift.explorer.ui.Tab;
import com.sift.explorer.ui.TabManager;
import com.sift.explorer.ui.TextTarget;
import com.sift.explorer.ui.TextViewerActivity;
import com.sift.explorer.util.DefaultApps;
import com.sift.explorer.util.DiskUsage;
import com.sift.explorer.util.MimeUtils;
import com.sift.explorer.util.Palettes;
import com.sift.explorer.util.Staging;
import com.sift.explorer.util.ThemePrefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The API's hands: every call goes through the same engines the screens use — FileSystemManager
 * for the six backends, FileOps for copies, Trash for the Recycle Bin, OpenWith for handoffs — so
 * an agent and a finger can never disagree about what an operation does.
 */
public class SiftBackend implements ApiBackend {

    private static final long TEXT_LIMIT = 5_000_000, TEXT_FORCE_LIMIT = 20_000_000;

    private final Context app;
    private final Jobs jobs = new Jobs();

    public SiftBackend(Context ctx) { this.app = ctx.getApplicationContext(); }

    private FileSystemManager fm() { return FileSystemManager.get(app); }

    // ---- locations --------------------------------------------------------------

    /** The filesystem a spec names; {@code path} picks the volume for "local". */
    private FileSystem fsFor(String spec, String path) throws Exception {
        if (spec == null || spec.isEmpty() || spec.equals("local")) {
            return path == null || path.isEmpty() ? fm().internalStorage() : fm().localFor(path);
        }
        if (spec.equals("root")) {
            if (!RootFileSystem.isAvailable()) {
                throw new ApiException(403, "root access is not available on this device",
                        "fs=root needs a rooted phone and Sift allowed in the root manager");
            }
            return fm().root();
        }
        Connection c = fm().connections().find(spec);
        if (c == null) {
            throw new ApiException(404, "no filesystem \"" + spec + "\"",
                    "fs is local, root, or a connection id from GET /api/connections");
        }
        return fm().forConnection(c);
    }

    private static String pathOr(FileSystem fs, String path) {
        return path == null || path.isEmpty() ? fs.getRootPath() : path;
    }

    private static String specOf(FileSystem fs) {
        String id = fs.connectionId();
        return id != null ? id : "root".equals(fs.getType()) ? "root" : "local";
    }

    private FileItem item(FileSystem fs, String path) throws Exception {
        if (!fs.exists(path)) throw new FileNotFoundException(path);
        return fs.stat(path);
    }

    private List<FileItem> items(FileSystem fs, List<String> paths) throws Exception {
        List<FileItem> out = new ArrayList<>();
        for (String p : paths) out.add(item(fs, p));
        return out;
    }

    private static JSONObject itemJson(FileItem f) throws Exception {
        return new JSONObject()
                .put("name", f.name)
                .put("path", f.path)
                .put("fs", specOf(f.fs))
                .put("dir", f.isDirectory)
                .put("symlink", f.isSymlink)
                .put("hidden", f.isHidden())
                .put("size", f.isDirectory ? JSONObject.NULL : f.size)
                .put("modified", f.lastModified == 0 ? JSONObject.NULL : f.lastModified)
                .put("permissions", f.permissions == null ? JSONObject.NULL : f.permissions)
                .put("mime", f.isDirectory ? JSONObject.NULL : MimeUtils.mimeType(f))
                .put("kind", MimeUtils.categoryOf(f).name().toLowerCase(Locale.ROOT));
    }

    private static JSONObject fsJson(FileSystem fs) throws Exception {
        JSONObject o = new JSONObject()
                .put("fs", specOf(fs)).put("type", fs.getType())
                .put("name", fs.getDisplayName()).put("root", fs.getRootPath());
        long[] u = fs.getUsage();
        if (u != null && u.length >= 2 && u[1] > 0) {
            o.put("used", u[0]).put("total", u[1]).put("free", Math.max(0, u[1] - u[0]));
        }
        return o;
    }

    @Override public JSONObject instance() throws Exception {
        PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
        long code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        boolean allFiles = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
        return new JSONObject()
                .put("app", "Sift")
                .put("package", app.getPackageName())
                .put("version", pi.versionName)
                .put("version_code", code)
                .put("api", ApiRouter.API_VERSION)
                .put("address", ApiStatus.address())
                .put("device", Build.MANUFACTURER + " " + Build.MODEL)
                .put("android", Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("permissions", new JSONObject()
                        .put("all_files_access", allFiles)
                        .put("display_over_apps", FloatingImageService.canFloat(app))
                        .put("install_apps", Build.VERSION.SDK_INT < 26 || app.getPackageManager().canRequestPackageInstalls()))
                .put("on_screen", MainThread.get(() -> Foreground.top() != null));
    }

    @Override public JSONArray locations() throws Exception {
        JSONArray a = new JSONArray();
        for (LocalFileSystem fs : fm().localRoots()) a.put(fsJson(fs));
        boolean root = RootFileSystem.isAvailable();
        a.put(new JSONObject().put("fs", "root").put("type", "root").put("name", "Root (/)")
                .put("root", "/").put("available", root));
        for (Connection c : fm().connections().getAll()) {
            a.put(new JSONObject().put("fs", c.id).put("type", c.type).put("name", c.displayName())
                    .put("root", c.initialPath == null || c.initialPath.isEmpty() ? JSONObject.NULL : c.initialPath));
        }
        return a;
    }

    // ---- connections ------------------------------------------------------------

    /** Never the password or the private key: an agent that needs them already has them. */
    private static JSONObject connJson(Connection c) throws Exception {
        return new JSONObject()
                .put("id", c.id).put("type", c.type).put("name", c.displayName())
                .put("host", c.host).put("port", c.effectivePort())
                .put("username", c.username == null ? JSONObject.NULL : c.username)
                .put("domain", c.domain == null ? JSONObject.NULL : c.domain)
                .put("share", c.share == null ? JSONObject.NULL : c.share)
                .put("initial_path", c.initialPath == null ? JSONObject.NULL : c.initialPath)
                .put("has_password", c.password != null && !c.password.isEmpty())
                .put("has_private_key", c.privateKey != null && !c.privateKey.isEmpty());
    }

    @Override public JSONArray connections() throws Exception {
        JSONArray a = new JSONArray();
        for (Connection c : fm().connections().getAll()) a.put(connJson(c));
        return a;
    }

    @Override public JSONObject connection(String id) throws Exception {
        Connection c = fm().connections().find(id);
        if (c == null) throw new ApiException(404, "no connection " + id);
        return connJson(c);
    }

    @Override public JSONObject saveConnection(String id, JSONObject b) throws Exception {
        Connection c;
        if (id == null) {
            c = new Connection();
            c.type = b.optString("type", "");
            if (!Connection.isNetwork(c.type)) {
                throw new ApiException(400, "type must be smb, sftp, ftp or ftps");
            }
            c.host = ApiRequest.need(b, "host");
        } else {
            c = fm().connections().find(id);
            if (c == null) throw new ApiException(404, "no connection " + id);
            if (b.has("type")) {
                String t = b.optString("type");
                if (!Connection.isNetwork(t)) throw new ApiException(400, "type must be smb, sftp, ftp or ftps");
                c.type = t;
            }
            if (b.has("host")) c.host = ApiRequest.need(b, "host");
        }
        if (b.has("name")) c.name = str(b, "name");
        if (b.has("port")) c.port = b.optInt("port", 0);
        if (b.has("username")) c.username = str(b, "username");
        if (b.has("password")) c.password = str(b, "password");
        if (b.has("domain")) c.domain = str(b, "domain");
        if (b.has("share")) c.share = str(b, "share");
        if (b.has("private_key")) c.privateKey = str(b, "private_key");
        if (b.has("initial_path")) c.initialPath = str(b, "initial_path");
        fm().connections().save(c);
        fm().evict(c.id); // the next use reconnects with what was just saved
        refreshUi();
        return connJson(c);
    }

    private static String str(JSONObject b, String k) {
        return b.isNull(k) ? null : b.optString(k, null);
    }

    @Override public void deleteConnection(String id) throws Exception {
        if (fm().connections().find(id) == null) throw new ApiException(404, "no connection " + id);
        fm().evict(id);
        fm().connections().delete(id);
        refreshUi();
    }

    // ---- files ------------------------------------------------------------------

    @Override public JSONObject list(String spec, String path, ListOptions o) throws Exception {
        FileSystem fs = fsFor(spec, path);
        String p = pathOr(fs, path);
        List<FileItem> all = fs.list(p);
        List<FileItem> shown = new ArrayList<>();
        String filter = o.filter.toLowerCase(Locale.ROOT);
        for (FileItem f : all) {
            if (!o.hidden && f.isHidden()) continue;
            if (!filter.isEmpty() && !f.name.toLowerCase(Locale.ROOT).contains(filter)) continue;
            shown.add(f);
        }
        sort(shown, o);
        int from = Math.min(o.offset, shown.size());
        int to = o.limit > 0 ? Math.min(shown.size(), from + o.limit) : shown.size();
        JSONArray entries = new JSONArray();
        for (FileItem f : shown.subList(from, to)) entries.put(itemJson(f));
        String parent = fs.isRoot(p) ? null : fs.getParent(p);
        return new JSONObject()
                .put("fs", specOf(fs)).put("filesystem", fs.getDisplayName())
                .put("path", p).put("parent", parent == null ? JSONObject.NULL : parent)
                .put("total", shown.size()).put("offset", from).put("entries", entries);
    }

    /** The browser's own order: folders first, then the field, ties by name. */
    private static void sort(List<FileItem> list, ListOptions o) {
        Comparator<FileItem> c;
        switch (o.sort) {
            case "size": c = (a, b) -> Long.compare(a.size, b.size); break;
            case "date": c = (a, b) -> Long.compare(a.lastModified, b.lastModified); break;
            case "type": c = (a, b) -> a.getExtension().compareToIgnoreCase(b.getExtension()); break;
            default: c = (a, b) -> a.name.compareToIgnoreCase(b.name);
        }
        if (o.desc) c = c.reversed();
        final Comparator<FileItem> byField = c;
        Collections.sort(list, (a, b) -> {
            if (o.foldersFirst && a.isDirectory != b.isDirectory) return a.isDirectory ? -1 : 1;
            int r = byField.compare(a, b);
            return r != 0 ? r : a.name.compareToIgnoreCase(b.name);
        });
    }

    @Override public JSONObject stat(String spec, String path) throws Exception {
        FileSystem fs = fsFor(spec, path);
        JSONObject o = itemJson(item(fs, path));
        o.put("bookmarked", fm().bookmarks().contains(fs.getType(), path, fs.connectionId()));
        return o;
    }

    @Override public Download read(String spec, String path) throws Exception {
        FileSystem fs = fsFor(spec, path);
        FileItem f = item(fs, path);
        if (f.isDirectory) {
            throw new ApiException(400, path + " is a folder",
                    "GET /api/files lists it; POST /api/files/zip packs it for download");
        }
        return new Download(fs.read(f), f.size, f.name, MimeUtils.mimeType(f));
    }

    @Override public JSONObject write(String spec, String path, InputStream body, long length,
                                      boolean overwrite) throws Exception {
        FileSystem fs = fsFor(spec, path);
        if (fs.exists(path)) {
            FileItem existing = fs.stat(path);
            if (existing.isDirectory) throw new ApiException(409, path + " is a folder");
            if (!overwrite) throw new ApiException(409, path + " already exists", "add ?overwrite=true to replace it");
        }
        String parent = fs.getParent(path);
        if (parent != null && !fs.exists(parent)) {
            throw new ApiException(404, "no folder " + parent, "POST /api/files/mkdir creates it");
        }
        long written = 0;
        try (OutputStream out = fs.write(path, length)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = body.read(buf)) != -1) { out.write(buf, 0, n); written += n; }
        }
        if (written < length) {
            throw new ApiException(400, "the upload stopped after " + written + " of " + length + " bytes");
        }
        return itemJson(fs.stat(path));
    }

    @Override public JSONObject mkdir(String spec, String path) throws Exception {
        FileSystem fs = fsFor(spec, path);
        if (fs.exists(path)) throw new ApiException(409, path + " already exists");
        fs.mkdirs(path);
        return itemJson(fs.stat(path));
    }

    @Override public JSONObject createFile(String spec, String path) throws Exception {
        FileSystem fs = fsFor(spec, path);
        if (fs.exists(path)) throw new ApiException(409, path + " already exists");
        fs.createFile(path);
        return itemJson(fs.stat(path));
    }

    @Override public JSONObject rename(String spec, String path, String newName) throws Exception {
        FileSystem fs = fsFor(spec, path);
        FileItem f = item(fs, path);
        String parent = fs.getParent(path);
        String target = parent == null ? newName : fs.childPath(parent, newName);
        if (fs.exists(target)) throw new ApiException(409, target + " already exists");
        fs.rename(f, newName);
        return itemJson(fs.stat(target));
    }

    @Override public JSONObject delete(String spec, List<String> paths, Boolean permanent) throws Exception {
        FileSystem fs = fsFor(spec, paths.get(0));
        boolean toBin = permanent == null ? ThemePrefs.recycleBin(app) : !permanent;
        JSONArray deleted = new JSONArray(), binned = new JSONArray(), failed = new JSONArray();
        for (String p : paths) {
            try {
                FileItem f = item(fs, p);
                if (toBin) {
                    TrashEntry e = Trash.moveToTrash(fs, f, fm().trash());
                    binned.put(new JSONObject().put("path", p).put("trash_id", e.id));
                } else {
                    fs.delete(f);
                    deleted.put(p);
                }
            } catch (Exception e) {
                failed.put(new JSONObject().put("path", p).put("error", msg(e)));
            }
        }
        refreshUi();
        return new JSONObject().put("recycle_bin", toBin)
                .put("deleted", deleted).put("binned", binned).put("failed", failed);
    }

    @Override public JSONObject transfer(String kind, String spec, List<String> paths, String destSpec,
                                         String dest, long waitMs) throws Exception {
        FileSystem fs = fsFor(spec, paths.get(0));
        FileSystem destFs = fsFor(destSpec, dest);
        List<FileItem> sources = items(fs, paths);
        JSONArray src = new JSONArray();
        for (String p : paths) src.put(p);
        JSONObject detail = new JSONObject().put("fs", specOf(fs)).put("paths", src)
                .put("dest_fs", specOf(destFs)).put("dest", dest);

        switch (kind) {
            case "copy": case "move":
                if (!destFs.exists(dest) || !destFs.stat(dest).isDirectory) {
                    throw new ApiException(404, "no folder " + dest, "dest is the folder to " + kind + " into");
                }
                break;
            case "zip":
                if (destFs.exists(dest)) throw new ApiException(409, dest + " already exists", "dest is the new .zip file's path");
                break;
            case "extract":
                if (sources.get(0).isDirectory) throw new ApiException(400, paths.get(0) + " is a folder, not a zip");
                break;
        }

        Jobs.Job job = jobs.create(kind, detail);
        job.after = this::refreshUi;
        switch (kind) {
            case "copy": job.task = FileOps.copy(sources, destFs, dest, false, job); break;
            case "move": job.task = FileOps.copy(sources, destFs, dest, true, job); break;
            case "zip": job.task = FileOps.compress(sources, destFs, dest, job); break;
            default: job.task = FileOps.extract(sources.get(0), destFs, dest, job);
        }
        return jobs.await(job, waitMs);
    }

    @Override public JSONObject properties(String spec, List<String> paths) throws Exception {
        FileSystem fs = fsFor(spec, paths.get(0));
        List<FileItem> list = items(fs, paths);
        long[] acc = new long[3]; // bytes, files, folders
        for (FileItem f : list) tally(f, acc);
        JSONObject o = new JSONObject().put("size", acc[0]).put("files", acc[1]).put("folders", acc[2]);
        if (list.size() == 1) o.put("item", itemJson(list.get(0)));
        else o.put("items", list.size());
        return o;
    }

    private static void tally(FileItem f, long[] acc) {
        if (!f.isDirectory) { acc[0] += f.size; acc[1]++; return; }
        acc[2]++;
        try { for (FileItem k : f.fs.list(f.path)) tally(k, acc); } catch (Exception ignore) {}
    }

    @Override public JSONObject diskUsage(String spec, String path, int limit) throws Exception {
        FileSystem fs = fsFor(spec, path);
        if (!"local".equals(fs.getType()) && !"root".equals(fs.getType())) {
            throw new ApiException(400, "disk usage isn't available for network shares",
                    "the app says the same; POST /api/files/properties sums a folder");
        }
        String p = pathOr(fs, path);
        DiskUsage.Node root = DiskUsage.scan(fs, p, fs.nameOf(p), new DiskUsage.Progress() {
            @Override public void onProgress(long bytesSoFar, String currentPath) {}
            @Override public boolean isCancelled() { return false; }
        });
        JSONArray kids = new JSONArray();
        DiskUsage.Node[] children = root.children == null ? new DiskUsage.Node[0] : root.children;
        for (int i = 0; i < children.length && (limit <= 0 || i < limit); i++) {
            DiskUsage.Node n = children[i];
            kids.put(new JSONObject().put("name", n.name).put("path", n.path).put("dir", n.isDir).put("size", n.size));
        }
        JSONObject o = new JSONObject().put("fs", specOf(fs)).put("path", p).put("size", root.size)
                .put("children", kids).put("child_count", children.length);
        long[] u = fs.getUsage();
        if (u != null && u.length >= 2 && u[1] > 0) o.put("volume", new JSONObject().put("used", u[0]).put("total", u[1]));
        return o;
    }

    // ---- jobs -------------------------------------------------------------------

    @Override public JSONArray jobs() { return jobs.all(); }

    @Override public JSONObject job(String id) { return jobs.get(id); }

    @Override public JSONObject cancelJob(String id) { return jobs.cancel(id); }

    // ---- bookmarks and the Recycle Bin ------------------------------------------

    @Override public JSONArray bookmarks() {
        JSONArray a = new JSONArray();
        try {
            for (Bookmark b : fm().bookmarks().getAll()) {
                a.put(new JSONObject().put("label", b.label).put("type", b.type).put("path", b.path)
                        .put("fs", b.connectionId != null ? b.connectionId : "root".equals(b.type) ? "root" : "local"));
            }
        } catch (Exception ignore) {}
        return a;
    }

    @Override public JSONObject addBookmark(String spec, String path, String label) throws Exception {
        FileSystem fs = fsFor(spec, path);
        FileItem f = item(fs, path);
        if (!f.isDirectory) throw new ApiException(400, path + " is a file; bookmarks are folders");
        String name = label == null || label.isEmpty() ? (fs.isRoot(path) ? fs.getDisplayName() : f.name) : label;
        fm().bookmarks().add(new Bookmark(name, fs.getType(), path, fs.connectionId()));
        refreshUi();
        return new JSONObject().put("label", name).put("type", fs.getType()).put("path", path).put("fs", specOf(fs));
    }

    @Override public boolean removeBookmark(String spec, String path) throws Exception {
        String type, conn = null;
        if (spec.equals("local")) type = "local";
        else if (spec.equals("root")) type = "root";
        else {
            Connection c = fm().connections().find(spec);
            if (c == null) return false;
            type = c.type;
            conn = c.id;
        }
        if (!fm().bookmarks().contains(type, path, conn)) return false;
        fm().bookmarks().remove(type, path, conn);
        refreshUi();
        return true;
    }

    private static JSONObject trashJson(TrashEntry e) throws Exception {
        return new JSONObject().put("id", e.id).put("name", e.name).put("dir", e.isDirectory)
                .put("original_folder", e.originalParent).put("deleted", e.deletedAt)
                .put("fs", e.connectionId != null ? e.connectionId : e.fsType);
    }

    private TrashEntry trashEntry(String id) {
        for (TrashEntry e : fm().trash().getAll()) if (e.id.equals(id)) return e;
        throw new ApiException(404, "nothing in the Recycle Bin with id " + id, "GET /api/trash lists it");
    }

    @Override public JSONArray trash() {
        JSONArray a = new JSONArray();
        try { for (TrashEntry e : fm().trash().getAll()) a.put(trashJson(e)); } catch (Exception ignore) {}
        return a;
    }

    @Override public JSONObject restoreTrash(String id) throws Exception {
        TrashEntry e = trashEntry(id);
        Trash.restore(fm(), e, fm().trash());
        refreshUi();
        return trashJson(e).put("restored_to", e.originalParent);
    }

    @Override public JSONObject purgeTrash(String id) throws Exception {
        TrashEntry e = trashEntry(id);
        Trash.deleteForever(fm(), e, fm().trash());
        refreshUi();
        return trashJson(e).put("deleted_forever", true);
    }

    @Override public JSONObject emptyTrash() throws Exception {
        int n = 0;
        JSONArray failed = new JSONArray();
        for (TrashEntry e : fm().trash().getAll()) {
            try { Trash.deleteForever(fm(), e, fm().trash()); n++; }
            catch (Exception ex) { failed.put(new JSONObject().put("id", e.id).put("error", msg(ex))); }
        }
        refreshUi();
        return new JSONObject().put("deleted_forever", n).put("failed", failed);
    }

    // ---- the screen -------------------------------------------------------------

    private static JSONObject tabJson(Tab t, int current) throws Exception {
        return new JSONObject().put("id", t.id).put("title", t.title).put("fs", specOf(t.fs))
                .put("filesystem", t.fs.getDisplayName()).put("path", t.path).put("current", t.id == current);
    }

    @Override public JSONObject tabs() {
        try {
            return MainThread.get(() -> {
                ApiUi ui = ApiUi.Registry.get();
                int current = ui == null ? -1 : ui.currentTabId();
                JSONArray a = new JSONArray();
                for (Tab t : TabManager.get().tabs()) a.put(tabJson(t, current));
                return new JSONObject().put("tabs", a).put("on_screen", Foreground.top() != null);
            });
        } catch (Exception e) {
            throw new ApiException(503, msg(e));
        }
    }

    @Override public JSONObject openTab(String spec, String path) throws Exception {
        FileSystem fs = fsFor(spec, path);
        String p = pathOr(fs, path);
        if (!item(fs, p).isDirectory) {
            throw new ApiException(400, p + " is a file; a tab opens a folder", "POST /api/open opens a file");
        }
        JSONObject t = MainThread.get(() -> {
            ApiUi ui = ApiUi.Registry.get();
            if (ui != null) ui.openLocation(fs, p);
            else TabManager.get().add(new Tab(fs, p));
            TabManager tm = TabManager.get();
            return tabJson(tm.at(tm.count() - 1), -1).put("current", true);
        });
        return t.put("visible", bringToFront());
    }

    /** Starts or raises the main screen and reports, honestly, whether it ended up in front. */
    private boolean bringToFront() throws Exception {
        if (MainThread.get(() -> Foreground.top() instanceof MainActivity)) return true;
        Intent i = new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try { app.startActivity(i); } catch (Exception ignore) {}
        for (int n = 0; n < 15; n++) {
            Thread.sleep(100);
            if (MainThread.get(() -> Foreground.top() instanceof MainActivity)) return true;
        }
        return false;
    }

    @Override public JSONObject updateTab(int id, String path, Boolean current) throws Exception {
        Tab t = MainThread.get(() -> TabManager.get().byId(id));
        if (t == null) throw new ApiException(404, "no tab " + id, "GET /api/tabs lists them");
        FileItem dir = null;
        if (path != null) {
            dir = item(t.fs, path);
            if (!dir.isDirectory) throw new ApiException(400, path + " is a file; a tab shows a folder");
        }
        final FileItem target = dir;
        return MainThread.get(() -> {
            ApiUi ui = ApiUi.Registry.get();
            if (target != null) {
                if (ui != null) ui.navigateTab(id, target);
                else { t.back.push(t.path); t.path = target.path; t.title = t.fs.isRoot(target.path) ? t.fs.getDisplayName() : target.name; }
            }
            if (Boolean.TRUE.equals(current) && ui != null) ui.selectTab(id);
            return tabJson(t, ui == null ? -1 : ui.currentTabId());
        });
    }

    @Override public boolean closeTab(int id) {
        try {
            return MainThread.get(() -> {
                TabManager tm = TabManager.get();
                if (tm.byId(id) == null) return false;
                ApiUi ui = ApiUi.Registry.get();
                if (ui != null) ui.closeTab(id);
                else tm.remove(tm.indexOf(id));
                return true;
            });
        } catch (Exception e) {
            throw new ApiException(503, msg(e));
        }
    }

    private Activity onScreen(String what) throws Exception {
        Activity a = MainThread.get(Foreground::top);
        if (a == null) {
            throw new ApiException(409, "Sift is not on screen, and Android drops " + what + " from an app that isn't",
                    "POST /api/tabs brings Sift up first; GET /api/files/content fetches the file directly");
        }
        return a;
    }

    /** A real File for an item: itself if local, else a fresh copy in its own staging folder. */
    private File localCopy(FileItem f) throws Exception {
        File local = f.asLocalFile();
        if (local != null) return local;
        File dir = Staging.dirFor(app, f);
        dir.mkdirs();
        Staging.clear(dir);
        File out = new File(dir, f.name);
        try (InputStream in = f.fs.read(f); OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
        }
        return out;
    }

    @Override public JSONObject open(String spec, String path, String how) throws Exception {
        FileSystem fs = fsFor(spec, path);
        FileItem f = item(fs, path);
        if (f.isDirectory) throw new ApiException(400, path + " is a folder", "POST /api/tabs opens a folder");
        onScreen("an app it opens");

        String opened;
        if (how.equals("default") && MimeUtils.isImage(f)) {
            // The gallery over the folder's images, as tapping one does.
            List<FileItem> imgs = new ArrayList<>();
            String parent = fs.getParent(path);
            try {
                for (FileItem k : fs.list(parent)) if (MimeUtils.isImage(k) && !k.isHidden()) imgs.add(k);
                Collections.sort(imgs, (a, b) -> a.name.compareToIgnoreCase(b.name));
            } catch (Exception ignore) {}
            int idx = imgs.indexOf(f);
            if (idx < 0) { imgs = Collections.singletonList(f); idx = 0; }
            final List<FileItem> list = imgs;
            final int start = idx;
            MainThread.run(() -> { ImageGallery.set(list, start); ImageViewerActivity.open(onScreen("the viewer")); });
            opened = "gallery";
        } else if (how.equals("text") || (how.equals("default") && MimeUtils.isTextLike(f) && f.size < TEXT_LIMIT)) {
            if (f.size >= TEXT_FORCE_LIMIT) throw new ApiException(413, "too large to open as text", "the app refuses it too");
            File local = localCopy(f);
            MainThread.run(() -> { TextTarget.set(f, local, f.name); TextViewerActivity.open(onScreen("the editor")); });
            opened = "text";
        } else {
            File local = localCopy(f);
            String mime = MimeUtils.mimeType(f);
            String typeKey = MimeUtils.categoryOf(f).name();
            DefaultApps defaults = new DefaultApps(app);
            boolean picker = how.equals("picker");
            MainThread.run(() -> {
                Activity a = onScreen("an app it opens");
                if (picker) OpenWith.picker(a, local, mime, typeKey, MimeUtils.typeLabel(f), defaults);
                else OpenWith.open(a, local, mime, typeKey, MimeUtils.typeLabel(f), defaults);
            });
            ComponentName def = defaults.get(typeKey);
            opened = picker || def == null ? "picker" : def.flattenToShortString();
        }
        return new JSONObject().put("ok", true).put("opened", opened).put("item", itemJson(f));
    }

    @Override public JSONObject share(String spec, List<String> paths) throws Exception {
        FileSystem fs = fsFor(spec, paths.get(0));
        List<FileItem> list = items(fs, paths);
        onScreen("a share sheet");
        ArrayList<Uri> uris = new ArrayList<>();
        JSONArray skipped = new JSONArray();
        for (FileItem f : list) {
            if (f.isDirectory) { skipped.put(f.path); continue; }
            File local = f.asLocalFile();
            if (local == null) {
                local = new File(app.getCacheDir(), "share/" + f.name);
                local.getParentFile().mkdirs();
                try (InputStream in = f.fs.read(f); OutputStream os = new FileOutputStream(local)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
                }
            }
            uris.add(FileProvider.getUriForFile(app, app.getPackageName() + ".fileprovider", local));
        }
        if (uris.isEmpty()) throw new ApiException(400, "nothing to share — folders can't be shared", "zip it first");
        MainThread.run(() -> {
            Intent i = new Intent(uris.size() > 1 ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND);
            i.setType("*/*");
            if (uris.size() > 1) i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            else i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            onScreen("a share sheet").startActivity(Intent.createChooser(i, "Share"));
        });
        return new JSONObject().put("ok", true).put("shared", uris.size()).put("skipped_folders", skipped);
    }

    @Override public JSONObject popOut(String spec, String path) throws Exception {
        FileSystem fs = fsFor(spec, path);
        FileItem f = item(fs, path);
        if (!MimeUtils.isImage(f)) throw new ApiException(400, path + " is not an image");
        if (!FloatingImageService.canFloat(app)) {
            throw new ApiException(403, "Sift may not display over other apps",
                    "grant it on the phone: Settings > Apps > Sift > Display over other apps");
        }
        File local = localCopy(f);
        MainThread.run(() -> FloatingImageService.show(app, local, f.name));
        return new JSONObject().put("ok", true).put("item", itemJson(f));
    }

    // ---- settings ---------------------------------------------------------------

    private static final String[] SORT_NAMES = {"name", "size", "date", "type"};

    @Override public JSONObject settings() {
        try {
            Palettes.Palette pal = ThemePrefs.palette(app);
            int sort = ThemePrefs.sortMode(app);
            return new JSONObject()
                    .put("palette", pal.name)
                    .put("palette_follows_dark", pal.followsMode)
                    .put("dark", ThemePrefs.isDark(app))
                    .put("dark_in_force", ThemePrefs.nightMode(app) == AppCompatDelegate.MODE_NIGHT_YES)
                    .put("recycle_bin", ThemePrefs.recycleBin(app))
                    .put("sort", sort >= 0 && sort < SORT_NAMES.length ? SORT_NAMES[sort] : "name")
                    .put("sort_desc", ThemePrefs.sortDesc(app))
                    .put("api", new JSONObject().put("port", ApiPrefs.port(app)).put("lan", ApiPrefs.lan(app)));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @Override public JSONObject updateSettings(JSONObject b) throws Exception {
        for (java.util.Iterator<String> it = b.keys(); it.hasNext(); ) {
            String k = it.next();
            switch (k) {
                case "palette": case "dark": case "recycle_bin": case "sort": case "sort_desc": break;
                case "api": case "palette_follows_dark": case "dark_in_force":
                    throw new ApiException(400, "\"" + k + "\" can't be changed over the API",
                            k.equals("api") ? "the port and LAN access are set on the phone, under Local API" : "it follows from the palette");
                default:
                    throw new ApiException(400, "no setting \"" + k + "\"", "GET /api/settings lists them");
            }
        }
        Palettes.Palette pal = null;
        if (b.has("palette")) {
            pal = Palettes.byName(b.optString("palette"));
            if (pal == null) throw new ApiException(400, "no palette \"" + b.optString("palette") + "\"", "GET /api/palettes lists them");
        }
        if (b.has("dark") && !(b.get("dark") instanceof Boolean)) throw new ApiException(400, "dark must be true or false");
        if (b.has("recycle_bin") && !(b.get("recycle_bin") instanceof Boolean)) throw new ApiException(400, "recycle_bin must be true or false");
        if (b.has("sort_desc") && !(b.get("sort_desc") instanceof Boolean)) throw new ApiException(400, "sort_desc must be true or false");

        final Palettes.Palette chosen = pal;
        MainThread.run(() -> {
            boolean themeChanged = false;
            int nightBefore = ThemePrefs.nightMode(app);
            String palBefore = ThemePrefs.palette(app).name;
            if (chosen != null) ThemePrefs.setPalette(app, chosen.name);
            if (b.has("dark")) ThemePrefs.setDark(app, b.getBoolean("dark"));
            themeChanged = !ThemePrefs.palette(app).name.equals(palBefore);
            if (b.has("recycle_bin")) ThemePrefs.setRecycleBin(app, b.getBoolean("recycle_bin"));
            if (b.has("sort") || b.has("sort_desc")) {
                int mode = b.has("sort") ? java.util.Arrays.asList(SORT_NAMES).indexOf(b.getString("sort")) : ThemePrefs.sortMode(app);
                boolean desc = b.has("sort_desc") ? b.getBoolean("sort_desc") : ThemePrefs.sortDesc(app);
                ThemePrefs.setSort(app, mode, desc);
                Tab.defaultSort = mode;
                Tab.defaultSortDesc = desc;
            }
            // A night-mode change recreates every screen by itself; a palette change alone doesn't.
            if (themeChanged && ThemePrefs.nightMode(app) == nightBefore) {
                for (Activity a : Foreground.live()) a.recreate();
            } else {
                ApiUi ui = ApiUi.Registry.get();
                if (ui != null) ui.refreshFromApi();
            }
        });
        return settings();
    }

    @Override public JSONArray palettes() {
        JSONArray a = new JSONArray();
        String current = ThemePrefs.palette(app).name;
        try {
            for (Palettes.Palette p : Palettes.ALL) {
                a.put(new JSONObject().put("name", p.name).put("label", p.label)
                        .put("follows_dark", p.followsMode).put("mono", p.mono).put("light", p.light)
                        .put("accent", p.isDynamic() ? JSONObject.NULL : hex(p.accent))
                        .put("background", p.isDynamic() ? JSONObject.NULL : hex(p.background))
                        .put("current", p.name.equals(current)));
            }
        } catch (Exception ignore) {}
        return a;
    }

    private static String hex(int argb) { return String.format("#%06X", argb & 0xFFFFFF); }

    @Override public JSONArray defaultApps() {
        JSONArray a = new JSONArray();
        DefaultApps d = new DefaultApps(app);
        try {
            for (MimeUtils.Category c : MimeUtils.Category.values()) {
                ComponentName cn = d.get(c.name());
                if (cn != null) a.put(new JSONObject().put("type", c.name().toLowerCase(Locale.ROOT))
                        .put("app", cn.getPackageName()).put("component", cn.flattenToShortString()));
            }
        } catch (Exception ignore) {}
        return a;
    }

    @Override public boolean clearDefaultApp(String type) {
        String key = type.toUpperCase(Locale.ROOT);
        DefaultApps d = new DefaultApps(app);
        if (d.get(key) == null) return false;
        d.clear(key);
        return true;
    }

    // ---- helpers ----------------------------------------------------------------

    /** The drawer and the open folder catch up with what the API just did. */
    private void refreshUi() {
        MainThread.post(() -> {
            ApiUi ui = ApiUi.Registry.get();
            if (ui != null) ui.refreshFromApi();
        });
    }

    private static String msg(Exception e) {
        String m = e.getMessage();
        return m != null ? m : e.getClass().getSimpleName();
    }
}
