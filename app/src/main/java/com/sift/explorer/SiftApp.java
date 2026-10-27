package com.sift.explorer;

import android.app.Application;

import com.sift.explorer.api.ApiService;
import com.sift.explorer.api.Foreground;
import com.sift.explorer.ui.Tab;
import com.sift.explorer.util.ThemePrefs;

public class SiftApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        // Before the first screen opens, so the API always knows what is on screen.
        Foreground.install(this);
        ThemePrefs.applyNightMode(this);
        Tab.defaultSort = ThemePrefs.sortMode(this);
        Tab.defaultSortDesc = ThemePrefs.sortDesc(this);
        ApiService.startIfEnabled(this);
    }
}
