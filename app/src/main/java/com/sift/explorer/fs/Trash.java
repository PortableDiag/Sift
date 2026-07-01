package com.sift.explorer.fs;

/**
 * Recycle-bin operations. Trashing moves an item into a per-item folder under a
 * {@code .SiftTrash} directory ON THE SAME filesystem, so it is a fast
 * server-side move (no data transfer) and restore is just a move back.
 *
 * All methods perform I/O — call off the main thread.
 */
public class Trash {

    private static int SEQ = 0;

    /** A writable trash root for the given filesystem. */
    private static String trashRoot(FileSystem fs) {
        if ("root".equals(fs.getType())) return "/data/local/tmp/.SiftTrash";
        return fs.childPath(fs.getRootPath(), ".SiftTrash");
    }

    private static synchronized String newId() {
        return Long.toString(System.currentTimeMillis()) + "_" + (SEQ++);
    }

    public static TrashEntry moveToTrash(FileSystem fs, FileItem item, TrashStore store) throws Exception {
        String id = newId();
        String itemDir = fs.childPath(trashRoot(fs), id);
        fs.mkdirs(itemDir);
        if (!fs.move(item, itemDir)) throw new Exception("Could not move to Recycle Bin");

        TrashEntry e = new TrashEntry();
        e.id = id;
        e.fsType = fs.getType();
        e.connectionId = fs.connectionId();
        e.name = item.name;
        e.isDirectory = item.isDirectory;
        e.originalParent = fs.getParent(item.path);
        e.trashDir = itemDir;
        e.deletedAt = System.currentTimeMillis();
        store.add(e);
        return e;
    }

    public static void restore(FileSystemManager fm, TrashEntry e, TrashStore store) throws Exception {
        FileSystem fs = fm.resolveFs(e.fsType, e.connectionId, e.originalParent);
        String trashedPath = fs.childPath(e.trashDir, e.name);
        FileItem item = new FileItem(fs, trashedPath, e.name, e.isDirectory, false, 0, 0, null);
        fs.mkdirs(e.originalParent);
        fs.move(item, e.originalParent);
        // tidy up the now-empty per-item trash folder
        try { fs.delete(new FileItem(fs, e.trashDir, lastSeg(e.trashDir), true, false, 0, 0, null)); }
        catch (Exception ignore) {}
        store.remove(e.id);
    }

    public static void deleteForever(FileSystemManager fm, TrashEntry e, TrashStore store) throws Exception {
        FileSystem fs = fm.resolveFs(e.fsType, e.connectionId, e.originalParent);
        fs.delete(new FileItem(fs, e.trashDir, lastSeg(e.trashDir), true, false, 0, 0, null));
        store.remove(e.id);
    }

    private static String lastSeg(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }
}
