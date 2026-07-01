package com.sift.explorer.ui;

import com.sift.explorer.fs.FileSystem;

import java.util.ArrayDeque;
import java.util.Deque;

/** Mutable state for one open browser tab. Lives in {@link TabManager}. */
public class Tab {

    public static final int SORT_NAME = 0;
    public static final int SORT_SIZE = 1;
    public static final int SORT_DATE = 2;
    public static final int SORT_TYPE = 3;

    private static int SEQ = 1;

    /** Sort mode/direction new tabs open with; seeded from saved prefs at app startup. */
    public static int defaultSort = SORT_NAME;
    public static boolean defaultSortDesc = false;

    public final int id;
    public FileSystem fs;
    public String path;
    public final Deque<String> back = new ArrayDeque<>();
    public int sort = defaultSort;
    public boolean sortDesc = defaultSortDesc;
    public boolean grid = false;
    public boolean showHidden = false;
    public boolean foldersFirst = true;
    public String title = "";

    public Tab(FileSystem fs, String path) {
        this.id = SEQ++;
        this.fs = fs;
        this.path = path;
        this.title = fs.isRoot(path) ? fs.getDisplayName() : fs.nameOf(path);
        if (this.title == null || this.title.isEmpty()) this.title = fs.getDisplayName();
    }
}
