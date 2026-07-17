package com.sift.explorer.fs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Standard scoped/app-visible storage backed by {@link java.io.File}. */
public class LocalFileSystem extends FileSystem {

    private final String rootPath;
    private final String displayName;

    public LocalFileSystem(String rootPath, String displayName) {
        this.rootPath = rootPath;
        this.displayName = displayName;
    }

    @Override public String getId() { return "local:" + rootPath; }
    @Override public String getType() { return "local"; }
    @Override public String getDisplayName() { return displayName; }
    @Override public String getRootPath() { return rootPath; }
    @Override public boolean isLocal() { return true; }

    @Override public long[] getUsage() {
        try {
            android.os.StatFs s = new android.os.StatFs(rootPath);
            long total = s.getTotalBytes();
            long free = s.getAvailableBytes();
            return new long[]{ total - free, total };
        } catch (Exception e) { return null; }
    }

    @Override public List<FileItem> list(String path) throws Exception {
        File dir = new File(path);
        File[] children = dir.listFiles();
        if (children == null) {
            if (!dir.exists()) throw new IOException("Not found: " + path);
            throw new IOException("Permission denied: " + path);
        }
        List<FileItem> out = new ArrayList<>(children.length);
        for (File f : children) out.add(toItem(f));
        return out;
    }

    private FileItem toItem(File f) {
        boolean symlink = false;
        try {
            symlink = !f.getAbsolutePath().equals(f.getCanonicalPath()) && isSymlink(f);
        } catch (IOException ignore) {}
        return new FileItem(this, f.getAbsolutePath(), f.getName(), f.isDirectory(),
                symlink, f.length(), f.lastModified(), null);
    }

    private boolean isSymlink(File f) throws IOException {
        File parent = f.getParentFile();
        File canon = parent == null ? f : new File(parent.getCanonicalFile(), f.getName());
        return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
    }

    @Override public FileItem stat(String path) {
        return toItem(new File(path));
    }

    @Override public boolean exists(String path) { return new File(path).exists(); }

    @Override public void mkdirs(String path) throws Exception {
        File f = new File(path);
        if (!f.exists() && !f.mkdirs()) throw new IOException("Could not create " + path);
    }

    @Override public void createFile(String path) throws Exception {
        File f = new File(path);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        if (!f.createNewFile() && !f.exists()) throw new IOException("Could not create " + path);
    }

    @Override public void delete(FileItem item) throws Exception {
        deleteRecursive(new File(item.path));
    }

    private void deleteRecursive(File f) throws IOException {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        if (!f.delete() && f.exists()) throw new IOException("Could not delete " + f.getPath());
    }

    @Override public void rename(FileItem item, String newName) throws Exception {
        File src = new File(item.path);
        File dst = new File(src.getParentFile(), newName);
        if (!src.renameTo(dst)) throw new IOException("Rename failed");
    }

    @Override public boolean move(FileItem item, String destDir) {
        File src = new File(item.path);
        File dst = new File(destDir, item.name);
        return src.renameTo(dst);
    }

    @Override public InputStream read(FileItem item) throws Exception {
        return new FileInputStream(item.path);
    }

    @Override public OutputStream write(String path, long sizeHint) throws Exception {
        File f = new File(path);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        return new FileOutputStream(f);
    }
}
