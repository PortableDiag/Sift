package com.sift.explorer;

import android.app.Application;

import com.sift.explorer.util.ThemePrefs;

public class SiftApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        ThemePrefs.applyNightMode(this);
    }
}
