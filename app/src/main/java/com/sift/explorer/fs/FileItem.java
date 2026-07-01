package com.sift.explorer.fs;

import java.io.File;

/**
 * Immutable description of a single entry (file or directory) within some
 * {@link FileSystem}. The {@code path} is interpreted by the owning filesystem
 * (an absolute POSIX path for local/root/sftp, a {@code smb://} URL for SMB).
 */
public class FileItem {

    public final FileSystem fs;
    public final String path;
    public final String name;
    public final boolean isDirectory;
    public final boolean isSymlink;
    public final long size;
    public final long lastModified;   // epoch millis, 0 if unknown
    public final String permissions;  // e.g. "drwxr-xr-x", may be null

    public FileItem(FileSystem fs, String path, String name, boolean isDirectory,
                    boolean isSymlink, long size, long lastModified, String permissions) {
        this.fs = fs;
        this.path = path;
        this.name = name;
        this.isDirectory = isDirectory;
        this.isSymlink = isSymlink;
        this.size = size;
        this.lastModified = lastModified;
        this.permissions = permissions;
    }

    public String getExtension() {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase();
    }

    public boolean isHidden() {
        return name.startsWith(".");
    }

    /** Local File handle if this item lives on a directly-accessible filesystem, else null. */
    public File asLocalFile() {
        if (fs != null && fs.isLocal()) return new File(path);
        return null;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileItem)) return false;
        FileItem other = (FileItem) o;
        return path.equals(other.path) && fs == other.fs;
    }

    @Override public int hashCode() {
        return path.hashCode();
    }
}
