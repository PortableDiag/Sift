package com.sift.explorer.util;

import com.sift.explorer.fs.FileItem;
import com.sift.explorer.fs.FileSystem;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Builds a sized tree of a directory for the disk-usage view. The scan runs off the
 * main thread and is cancellable. Local storage is walked directly through
 * {@link java.io.File} for speed; other backends (root) go through {@link FileSystem#list}.
 * Symlinked directories are not descended into, so cycles cannot hang the scan.
 */
public final class DiskUsage {

    private DiskUsage() {}

    /** A node in the usage tree. Directory {@link #size} is the recursive total of its subtree. */
    public static final class Node {
        public final String name;
        public final String path;
        public final boolean isDir;
        public long size;
        public Node[] children;   // sorted desc by size; empty (not null) for scanned dirs
        public Node parent;
        /** Synthetic, non-navigable node representing unused volume space (root column only). */
        public boolean isFreeSpace;

        Node(String name, String path, boolean isDir) {
            this.name = name;
            this.path = path;
            this.isDir = isDir;
        }
    }

    /** A synthetic, non-navigable node representing {@code bytes} of unused volume space. */
    public static Node freeSpaceNode(long bytes) {
        Node n = new Node("Free space", null, false);
        n.size = bytes;
        n.isFreeSpace = true;
        return n;
    }

    /** Reports progress and lets the caller abort the walk. */
    public interface Progress {
        /** Called as directories are entered; {@code bytesSoFar} is the running subtree total. */
        void onProgress(long bytesSoFar, String currentPath);
        boolean isCancelled();
    }

    /** Aborts a scan when the caller cancels. */
    public static final class Cancelled extends Exception {}

    private static final Comparator<Node> BY_SIZE_DESC = (a, b) -> Long.compare(b.size, a.size);

    /**
     * Scans {@code rootPath} and returns its populated node. Throws {@link Cancelled} if the
     * caller cancelled, or a backend exception if the root itself is unreadable.
     */
    public static Node scan(FileSystem fs, String rootPath, String rootLabel, Progress cb) throws Exception {
        Node root = new Node(rootLabel, rootPath, true);
        long[] acc = {0};
        if (fs.isLocal()) scanLocal(new File(rootPath), root, cb, acc);
        else scanFs(fs, rootPath, root, cb, acc);
        sortTree(root);
        return root;
    }

    private static void scanLocal(File dir, Node node, Progress cb, long[] acc) throws Cancelled {
        if (cb.isCancelled()) throw new Cancelled();
        File[] kids = dir.listFiles();
        if (kids == null) { node.children = new Node[0]; return; }
        Node[] out = new Node[kids.length];
        int n = 0;
        for (File f : kids) {
            if (cb.isCancelled()) throw new Cancelled();
            boolean isDir = f.isDirectory() && !isSymlink(f);
            Node c = new Node(f.getName(), f.getAbsolutePath(), isDir);
            c.parent = node;
            if (isDir) {
                scanLocal(f, c, cb, acc);
            } else {
                c.size = f.length();
                acc[0] += c.size;
            }
            node.size += c.size;
            out[n++] = c;
        }
        node.children = Arrays.copyOf(out, n);
        cb.onProgress(acc[0], node.path);
    }

    private static void scanFs(FileSystem fs, String path, Node node, Progress cb, long[] acc) throws Cancelled {
        if (cb.isCancelled()) throw new Cancelled();
        List<FileItem> kids;
        try { kids = fs.list(path); }
        catch (Exception e) { node.children = new Node[0]; return; }  // unreadable dir → 0 bytes
        Node[] out = new Node[kids.size()];
        int n = 0;
        for (FileItem f : kids) {
            if (cb.isCancelled()) throw new Cancelled();
            boolean isDir = f.isDirectory && !f.isSymlink;
            Node c = new Node(f.name, f.path, isDir);
            c.parent = node;
            if (isDir) {
                scanFs(fs, f.path, c, cb, acc);
            } else {
                c.size = f.size;
                acc[0] += c.size;
            }
            node.size += c.size;
            out[n++] = c;
        }
        node.children = Arrays.copyOf(out, n);
        cb.onProgress(acc[0], node.path);
    }

    private static void sortTree(Node node) {
        if (node.children == null || node.children.length == 0) return;
        Arrays.sort(node.children, BY_SIZE_DESC);
        for (Node c : node.children) sortTree(c);
    }

    private static boolean isSymlink(File f) {
        try {
            File parent = f.getParentFile();
            File canon = parent == null ? f : new File(parent.getCanonicalFile(), f.getName());
            return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
        } catch (IOException e) {
            return false;
        }
    }
}
