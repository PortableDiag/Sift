package com.sift.explorer.ui;

import com.sift.explorer.fs.FileItem;

import java.util.List;

/**
 * In-process handoff of the image set from the browser to {@link ImageViewerActivity}.
 * FileItem holds a live FileSystem reference, so this avoids parcelling it through an Intent.
 */
public class ImageGallery {
    public static List<FileItem> items;
    public static int startIndex;

    public static void set(List<FileItem> items, int startIndex) {
        ImageGallery.items = items;
        ImageGallery.startIndex = startIndex;
    }
}
