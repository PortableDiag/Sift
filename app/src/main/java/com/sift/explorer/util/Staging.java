package com.sift.explorer.util;

import android.content.Context;

import com.sift.explorer.fs.FileItem;

import java.io.File;

/**
 * Where a remote or root file is copied before it is handed to another app.
 *
 * <p>{@link com.sift.explorer.ui.OpenWith} works out which folder a file belongs to by listing
 * the parent of the File it is handed. Staging everything into one flat directory therefore
 * offered every previously-opened file to the player as a sibling of this one. A directory per
 * source file holds exactly one file, so the player falls back to single-file playback — the
 * honest answer for a share whose other files we have not downloaded. It also stops two shares
 * holding the same filename from overwriting each other's copy.
 */
public final class Staging {

    private Staging() {}

    /** One cache directory per source file, keyed by its filesystem and path. */
    public static File dirFor(Context ctx, FileItem item) {
        String key = (item.fs == null ? "" : item.fs.getId()) + "\n" + item.path;
        return new File(ctx.getCacheDir(), "open/" + shortHash(key));
    }

    /** Empties a staging directory so a stale copy is never served. */
    public static void clear(File dir) {
        File[] stale = dir.listFiles();
        if (stale != null) for (File f : stale) f.delete();
    }

    private static String shortHash(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
