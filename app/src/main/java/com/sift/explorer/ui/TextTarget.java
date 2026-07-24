package com.sift.explorer.ui;

import com.sift.explorer.fs.FileItem;

import java.io.File;

/**
 * In-process handoff of the file to edit from the browser to {@link TextViewerActivity}.
 * {@code source} carries a live {@link com.sift.explorer.fs.FileSystem} reference (so we can
 * write edits back to a remote share), while {@code local} is the on-disk working copy the
 * editor reads from. Mirrors {@link ImageGallery} — avoids parcelling a FileSystem through an Intent.
 */
public class TextTarget {
    /** The original item (may live on a remote backend). Null if opening a bare local file. */
    public static FileItem source;
    /** Local working copy the editor loads and saves to. Never null. */
    public static File local;
    /** Title shown in the toolbar. */
    public static String title;

    public static void set(FileItem source, File local, String title) {
        TextTarget.source = source;
        TextTarget.local = local;
        TextTarget.title = title;
    }
}
