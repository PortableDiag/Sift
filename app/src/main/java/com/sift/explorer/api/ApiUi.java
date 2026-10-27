package com.sift.explorer.api;

import com.sift.explorer.fs.FileItem;
import com.sift.explorer.fs.FileSystem;

/**
 * What the API asks of the main screen, so a tab an agent opens appears while you watch. The
 * screen registers itself while it exists; every call is made on the main thread.
 */
public interface ApiUi {

    void openLocation(FileSystem fs, String path);

    void closeTab(int tabId);

    void selectTab(int tabId);

    /** Navigate a tab into a folder, as tapping it does (Back returns). */
    void navigateTab(int tabId, FileItem dir);

    /** The id of the tab on screen, or -1 for the new-tab page. */
    int currentTabId();

    /** Something the drawer shows changed: bookmarks, the Recycle Bin, a setting. */
    void refreshFromApi();

    final class Registry {
        private static ApiUi current;

        private Registry() {}

        public static void set(ApiUi ui) { current = ui; }

        public static void clear(ApiUi ui) { if (current == ui) current = null; }

        public static ApiUi get() { return current; }
    }
}
