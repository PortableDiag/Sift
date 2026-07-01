package com.sift.explorer;

import android.app.Application;

import com.sift.explorer.ui.Tab;
import com.sift.explorer.util.ThemePrefs;

public class SiftApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        ThemePrefs.applyNightMode(this);
        Tab.defaultSort = ThemePrefs.sortMode(this);
        Tab.defaultSortDesc = ThemePrefs.sortDesc(this);
    }
}
