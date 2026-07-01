package com.sift.explorer.fs;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * A storage backend. Every implementation exposes a uniform tree-of-entries API
 * so the browser UI never needs to know whether it is talking to local storage,
 * a rooted shell, an SMB share or an SFTP server.
 *
 * All methods (except the cheap path helpers and {@link #isLocal()}) perform I/O
 * and MUST be called off the main thread.
 */
public abstract class FileSystem {

    /** Stable identifier, unique per live instance (used to key tabs/caches). */
    public abstract String getId();

    /** One of: local, root, smb, sftp. */
    public abstract String getType();

    /** Human label shown in the UI ("Internal storage", "NAS (smb)", ...). */
    public abstract String getDisplayName();

    /** Path the browser opens to when this filesystem is first shown. */
    public abstract String getRootPath();

    public abstract List<FileItem> list(String path) throws Exception;

    public abstract FileItem stat(String path) throws Exception;

    public abstract boolean exists(String path);

    public abstract void mkdirs(String path) throws Exception;

    /** Creates an empty file. */
    public abstract void createFile(String path) throws Exception;

    /** Recursively deletes a file or directory. */
    public abstract void delete(FileItem item) throws Exception;

    public abstract void rename(FileItem item, String newName) throws Exception;

    /** Server-side move within the same filesystem; returns false if not possible. */
    public abstract boolean move(FileItem item, String destDir) throws Exception;

    public abstract InputStream read(FileItem item) throws Exception;

    /** Opens a stream to (over)write the file at {@code path}. */
    public abstract OutputStream write(String path, long sizeHint) throws Exception;

    /** True if entries map onto java.io.File and can be handed to a FileProvider. */
    public boolean isLocal() { return false; }

    /** Id of the saved connection backing this filesystem (smb/sftp), else null. */
    public String connectionId() { return null; }

    /** Releases sockets/sessions. Safe to call repeatedly. */
    public void close() {}

    // ---- path helpers (pure, no I/O) -------------------------------------

    /** Path separator for this scheme. */
    protected char sep() { return '/'; }

    public String getParent(String path) {
        if (path == null) return null;
        String p = path;
        // strip a single trailing separator (but keep "smb://host/" style roots intact)
        if (p.length() > 1 && p.charAt(p.length() - 1) == sep()) {
            p = p.substring(0, p.length() - 1);
        }
        int idx = p.lastIndexOf(sep());
        if (idx < 0) return null;
        if (idx == 0) return String.valueOf(sep()); // root
        String parent = p.substring(0, idx);
        if (parent.isEmpty()) return String.valueOf(sep());
        return parent;
    }

    public String nameOf(String path) {
        if (path == null) return "";
        String p = path;
        if (p.length() > 1 && p.charAt(p.length() - 1) == sep()) {
            p = p.substring(0, p.length() - 1);
        }
        int idx = p.lastIndexOf(sep());
        return idx < 0 ? p : p.substring(idx + 1);
    }

    public String childPath(String parent, String name) {
        if (parent == null || parent.isEmpty()) return name;
        if (parent.charAt(parent.length() - 1) == sep()) return parent + name;
        return parent + sep() + name;
    }

    /** Whether the given path is at/above the navigable root of this filesystem. */
    public boolean isRoot(String path) {
        String root = getRootPath();
        return path == null || path.equals(root) || path.isEmpty();
    }
}
