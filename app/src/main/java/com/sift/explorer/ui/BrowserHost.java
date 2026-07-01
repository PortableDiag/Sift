package com.sift.explorer.ui;

import com.sift.explorer.fs.FileSystem;

/** Contract between the page fragments and the hosting activity. */
public interface BrowserHost {

    /** Materialise the phantom "new tab" page into a real tab at the chosen location. */
    void openLocation(FileSystem fs, String path);

    /** A tab's current folder/title changed; refresh the tab strip. */
    void onTabUpdated(int tabId);

    /** Close the tab with the given id. */
    void closeTab(int tabId);

    /** Bookmarks were added/removed; refresh the drawer. */
    void onBookmarksChanged();
}
