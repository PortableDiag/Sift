package com.sift.explorer.api;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/**
 * Which of Sift's screens exist, and which one is in front of the user.
 *
 * <p>Opening a file in another app, or a share chooser, needs a visible activity: Android drops
 * an activity start from an app that is not on screen. The API says so instead of guessing, so
 * this has to be right — hence installed from {@code SiftApp.onCreate}, before the first screen
 * opens (Lull learned that registering from the service misses the very first activity).
 */
public final class Foreground implements Application.ActivityLifecycleCallbacks {

    private static final List<Activity> LIVE = new ArrayList<>();
    private static Activity resumed;

    private Foreground() {}

    public static void install(Application app) {
        app.registerActivityLifecycleCallbacks(new Foreground());
    }

    /** The activity the user is looking at, or null. Main thread only. */
    public static Activity top() { return resumed; }

    /** Every live activity, for a theme change to recreate. Main thread only. */
    public static List<Activity> live() { return new ArrayList<>(LIVE); }

    @Override public void onActivityCreated(Activity a, Bundle b) { LIVE.add(a); }
    @Override public void onActivityResumed(Activity a) { resumed = a; }
    @Override public void onActivityPaused(Activity a) { if (resumed == a) resumed = null; }
    @Override public void onActivityDestroyed(Activity a) { LIVE.remove(a); if (resumed == a) resumed = null; }
    @Override public void onActivityStarted(Activity a) {}
    @Override public void onActivityStopped(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
}
