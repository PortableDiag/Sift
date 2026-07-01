package com.sift.explorer.fs;

import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Background engine for bulk file operations (copy / move / delete / zip / unzip)
 * with progress reporting and cancellation. Works across heterogeneous
 * filesystems by streaming bytes; uses server-side moves when source and
 * destination share a backend.
 */
public class FileOps {

    private static final ExecutorService EXEC = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int BUF = 1 << 16;

    public static class Progress {
        public String name = "";
        public long doneBytes, totalBytes;
        public int fileIndex, fileCount;
    }

    public interface Listener {
        void onProgress(Progress p);
        void onDone(boolean ok, String error);
    }

    public static class Task {
        volatile boolean cancelled;
        public void cancel() { cancelled = true; }
        public boolean isCancelled() { return cancelled; }
    }

    private static void post(Listener l, Progress p) {
        final Progress snapshot = new Progress();
        snapshot.name = p.name; snapshot.doneBytes = p.doneBytes; snapshot.totalBytes = p.totalBytes;
        snapshot.fileIndex = p.fileIndex; snapshot.fileCount = p.fileCount;
        MAIN.post(() -> l.onProgress(snapshot));
    }

    private static void done(Listener l, boolean ok, String err) {
        MAIN.post(() -> l.onDone(ok, err));
    }

    // ---- copy / move -----------------------------------------------------

    public static Task copy(List<FileItem> sources, FileSystem destFs, String destDir,
                            boolean move, Listener l) {
        Task task = new Task();
        EXEC.execute(() -> {
            Progress p = new Progress();
            try {
                p.fileCount = countFiles(sources);
                p.totalBytes = totalBytes(sources);
                post(l, p);
                for (FileItem item : sources) {
                    if (task.cancelled) { done(l, false, "Cancelled"); return; }
                    boolean sameFs = item.fs == destFs;
                    if (move && sameFs) {
                        // Fast server-side move of the whole subtree.
                        if (destFs.move(item, destDir)) {
                            p.fileIndex += countFiles(java(item));
                            p.doneBytes += item.isDirectory ? subtreeBytes(item) : item.size;
                            p.name = item.name;
                            post(l, p);
                            continue;
                        }
                    }
                    copyItem(item, destFs, destDir, p, l, task);
                    if (move && !task.cancelled) item.fs.delete(item);
                }
                done(l, !task.cancelled, task.cancelled ? "Cancelled" : null);
            } catch (Exception e) {
                done(l, false, msg(e));
            }
        });
        return task;
    }

    private static List<FileItem> java(FileItem item) {
        java.util.ArrayList<FileItem> one = new java.util.ArrayList<>();
        one.add(item);
        return one;
    }

    private static void copyItem(FileItem item, FileSystem destFs, String destDir,
                                 Progress p, Listener l, Task task) throws Exception {
        if (task.cancelled) return;
        String targetName = uniqueName(destFs, destDir, item.name);
        String target = destFs.childPath(destDir, targetName);
        if (item.isDirectory) {
            destFs.mkdirs(target);
            List<FileItem> kids = item.fs.list(item.path);
            for (FileItem kid : kids) copyItem(kid, destFs, target, p, l, task);
        } else {
            p.name = item.name;
            post(l, p);
            InputStream in = null; OutputStream out = null;
            try {
                in = item.fs.read(item);
                out = destFs.write(target, item.size);
                byte[] buf = new byte[BUF];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (task.cancelled) break;
                    out.write(buf, 0, n);
                    p.doneBytes += n;
                    post(l, p);
                }
            } finally {
                closeQuiet(in); closeQuiet(out);
            }
            p.fileIndex++;
            post(l, p);
        }
    }

    // ---- delete ----------------------------------------------------------

    public static Task delete(List<FileItem> items, Listener l) {
        Task task = new Task();
        EXEC.execute(() -> {
            Progress p = new Progress();
            p.fileCount = items.size();
            try {
                for (FileItem item : items) {
                    if (task.cancelled) { done(l, false, "Cancelled"); return; }
                    p.name = item.name;
                    post(l, p);
                    item.fs.delete(item);
                    p.fileIndex++;
                    post(l, p);
                }
                done(l, true, null);
            } catch (Exception e) {
                done(l, false, msg(e));
            }
        });
        return task;
    }

    // ---- zip / unzip -----------------------------------------------------

    public static Task compress(List<FileItem> items, FileSystem destFs, String zipPath, Listener l) {
        Task task = new Task();
        EXEC.execute(() -> {
            Progress p = new Progress();
            try {
                p.fileCount = countFiles(items);
                p.totalBytes = totalBytes(items);
                OutputStream raw = destFs.write(zipPath, p.totalBytes);
                ZipOutputStream zos = new ZipOutputStream(raw);
                try {
                    for (FileItem item : items) zipInto(zos, item, "", p, l, task);
                    zos.finish();
                } finally { closeQuiet(zos); }
                done(l, !task.cancelled, task.cancelled ? "Cancelled" : null);
            } catch (Exception e) {
                done(l, false, msg(e));
            }
        });
        return task;
    }

    private static void zipInto(ZipOutputStream zos, FileItem item, String prefix,
                                Progress p, Listener l, Task task) throws Exception {
        if (task.cancelled) return;
        String entryName = prefix.isEmpty() ? item.name : prefix + "/" + item.name;
        if (item.isDirectory) {
            zos.putNextEntry(new ZipEntry(entryName + "/"));
            zos.closeEntry();
            for (FileItem kid : item.fs.list(item.path)) zipInto(zos, kid, entryName, p, l, task);
        } else {
            zos.putNextEntry(new ZipEntry(entryName));
            p.name = item.name; post(l, p);
            InputStream in = item.fs.read(item);
            try {
                byte[] buf = new byte[BUF];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (task.cancelled) break;
                    zos.write(buf, 0, n);
                    p.doneBytes += n; post(l, p);
                }
            } finally { closeQuiet(in); }
            zos.closeEntry();
            p.fileIndex++; post(l, p);
        }
    }

    public static Task extract(FileItem zip, FileSystem destFs, String destDir, Listener l) {
        Task task = new Task();
        EXEC.execute(() -> {
            Progress p = new Progress();
            try {
                destFs.mkdirs(destDir);
                ZipInputStream zis = new ZipInputStream(zip.fs.read(zip));
                try {
                    ZipEntry e;
                    while ((e = zis.getNextEntry()) != null) {
                        if (task.cancelled) break;
                        String safe = sanitize(e.getName());
                        if (safe == null) continue;
                        String target = destFs.childPath(destDir, safe);
                        if (e.isDirectory()) {
                            destFs.mkdirs(target);
                        } else {
                            String parent = destFs.getParent(target);
                            if (parent != null) destFs.mkdirs(parent);
                            p.name = safe; p.fileIndex++; post(l, p);
                            OutputStream out = destFs.write(target, e.getSize());
                            try {
                                byte[] buf = new byte[BUF];
                                int n;
                                while ((n = zis.read(buf)) != -1) {
                                    if (task.cancelled) break;
                                    out.write(buf, 0, n);
                                    p.doneBytes += n; post(l, p);
                                }
                            } finally { closeQuiet(out); }
                        }
                        zis.closeEntry();
                    }
                } finally { closeQuiet(zis); }
                done(l, !task.cancelled, task.cancelled ? "Cancelled" : null);
            } catch (Exception ex) {
                done(l, false, msg(ex));
            }
        });
        return task;
    }

    /** Rejects path traversal (zip-slip). */
    private static String sanitize(String name) {
        String n = name.replace('\\', '/');
        if (n.startsWith("/")) n = n.substring(1);
        if (n.contains("../") || n.equals("..")) return null;
        if (n.isEmpty()) return null;
        return n;
    }

    // ---- helpers ---------------------------------------------------------

    private static String uniqueName(FileSystem fs, String dir, String name) {
        if (!fs.exists(fs.childPath(dir, name))) return name;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        for (int i = 1; i < 1000; i++) {
            String cand = base + (i == 1 ? " (copy)" : " (copy " + i + ")") + ext;
            if (!fs.exists(fs.childPath(dir, cand))) return cand;
        }
        return base + "-" + System.nanoTime() + ext;
    }

    private static int countFiles(List<FileItem> items) {
        int c = 0;
        for (FileItem i : items) {
            if (i.isDirectory) {
                try { c += countFiles(i.fs.list(i.path)); } catch (Exception e) { c += 1; }
            } else c++;
        }
        return c;
    }

    private static long totalBytes(List<FileItem> items) {
        long t = 0;
        for (FileItem i : items) t += i.isDirectory ? subtreeBytes(i) : i.size;
        return t;
    }

    private static long subtreeBytes(FileItem dir) {
        long t = 0;
        try { for (FileItem k : dir.fs.list(dir.path)) t += k.isDirectory ? subtreeBytes(k) : k.size; }
        catch (Exception ignore) {}
        return t;
    }

    private static void closeQuiet(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (Exception ignore) {}
    }

    private static String msg(Exception e) {
        String m = e.getMessage();
        return m != null ? m : e.getClass().getSimpleName();
    }
}
