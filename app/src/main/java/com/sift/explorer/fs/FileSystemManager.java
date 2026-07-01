package com.sift.explorer.fs;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Central factory + cache for filesystem instances. */
public class FileSystemManager {

    private static FileSystemManager INSTANCE;

    private final Context app;
    private final ConnectionStore store;
    private BookmarkStore bookmarkStore;
    private TrashStore trashStore;
    private RootFileSystem rootFs;
    private final Map<String, FileSystem> netCache = new HashMap<>();

    private FileSystemManager(Context ctx) {
        this.app = ctx.getApplicationContext();
        this.store = new ConnectionStore(app);
    }

    public synchronized BookmarkStore bookmarks() {
        if (bookmarkStore == null) bookmarkStore = new BookmarkStore(app);
        return bookmarkStore;
    }

    public synchronized TrashStore trash() {
        if (trashStore == null) trashStore = new TrashStore(app);
        return trashStore;
    }

    /** Resolves a filesystem by type/connection (used for bookmarks and trash). */
    public FileSystem resolveFs(String type, String connectionId, String hintPath) throws Exception {
        if ("root".equals(type)) return root();
        if (Connection.isNetwork(type)) {
            Connection c = store.find(connectionId);
            if (c == null) throw new Exception("Saved connection was removed");
            return forConnection(c);
        }
        return localFor(hintPath);
    }

    /** A LocalFileSystem rooted at the volume containing {@code path} (so "up" works). */
    public LocalFileSystem localFor(String path) {
        for (LocalFileSystem fs : localRoots()) {
            String r = fs.getRootPath();
            if (path.equals(r) || path.startsWith(r + "/")) return fs;
        }
        return new LocalFileSystem("/", "Device");
    }

    /** Resolves the filesystem for a bookmark; may connect — call off the main thread. */
    public FileSystem resolveBookmark(Bookmark b) throws Exception {
        if ("root".equals(b.type)) return root();
        if (Connection.isNetwork(b.type)) {
            Connection c = store.find(b.connectionId);
            if (c == null) throw new Exception("Saved connection was removed");
            return forConnection(c);
        }
        return localFor(b.path);
    }

    public static synchronized FileSystemManager get(Context ctx) {
        if (INSTANCE == null) INSTANCE = new FileSystemManager(ctx);
        return INSTANCE;
    }

    public ConnectionStore connections() { return store; }

    public LocalFileSystem internalStorage() {
        File root = Environment.getExternalStorageDirectory();
        return new LocalFileSystem(root.getAbsolutePath(), "Internal storage");
    }

    /** Internal storage plus any detected removable volumes. */
    public List<LocalFileSystem> localRoots() {
        List<LocalFileSystem> out = new ArrayList<>();
        out.add(internalStorage());
        try {
            File[] dirs = app.getExternalFilesDirs(null);
            String primary = Environment.getExternalStorageDirectory().getAbsolutePath();
            for (File d : dirs) {
                if (d == null) continue;
                // d looks like /storage/XXXX-XXXX/Android/data/<pkg>/files -> climb to volume root
                String p = d.getAbsolutePath();
                int idx = p.indexOf("/Android/data/");
                if (idx <= 0) continue;
                String vol = p.substring(0, idx);
                if (vol.equals(primary)) continue;
                if (new File(vol).canRead()) out.add(new LocalFileSystem(vol, "SD card"));
            }
        } catch (Exception ignore) {}
        return out;
    }

    public synchronized RootFileSystem root() {
        if (rootFs == null) rootFs = new RootFileSystem();
        return rootFs;
    }

    public synchronized FileSystem forConnection(Connection c) throws Exception {
        FileSystem cached = netCache.get(c.id);
        if (cached != null) return cached;
        FileSystem fs;
        if (Connection.TYPE_SFTP.equals(c.type)) fs = new SftpFileSystem(c);
        else if (Connection.TYPE_FTP.equals(c.type) || Connection.TYPE_FTPS.equals(c.type)) fs = new FtpFileSystem(c);
        else fs = new SmbFileSystem(c);
        netCache.put(c.id, fs);
        return fs;
    }

    /** Drops a cached network session (e.g. after edit/delete of a connection). */
    public synchronized void evict(String connectionId) {
        FileSystem fs = netCache.remove(connectionId);
        if (fs != null) fs.close();
    }
}
