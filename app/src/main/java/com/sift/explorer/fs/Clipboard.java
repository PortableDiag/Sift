package com.sift.explorer.fs;

import java.util.ArrayList;
import java.util.List;

/** Process-wide cut/copy buffer for file operations. */
public class Clipboard {

    public enum Mode { COPY, MOVE }

    private static final List<FileItem> items = new ArrayList<>();
    private static Mode mode = Mode.COPY;

    public static void set(List<FileItem> selection, Mode m) {
        items.clear();
        items.addAll(selection);
        mode = m;
    }

    public static boolean isEmpty() { return items.isEmpty(); }

    public static List<FileItem> items() { return new ArrayList<>(items); }

    public static Mode mode() { return mode; }

    public static int count() { return items.size(); }

    public static void clear() { items.clear(); }
}
