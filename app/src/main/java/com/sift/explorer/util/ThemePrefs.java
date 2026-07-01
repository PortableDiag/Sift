package com.sift.explorer.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;

import androidx.appcompat.app.AppCompatDelegate;

import com.sift.explorer.R;

/** Persists and applies the user's appearance choices: dark/light and accent. */
public class ThemePrefs {

    private static final String FILE = "sift_settings";
    private static final String K_NIGHT = "night";
    private static final String K_ACCENT = "accent";
    private static final String K_RECYCLE = "recycle_bin";
    private static final String K_SORT = "sort_mode";
    private static final String K_SORT_DESC = "sort_desc";

    public static final String BLUE = "blue";
    public static final String GREEN = "green";

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // Dark is the default.
    public static int nightMode(Context c) {
        return p(c).getInt(K_NIGHT, AppCompatDelegate.MODE_NIGHT_YES);
    }

    public static boolean isDark(Context c) {
        return nightMode(c) != AppCompatDelegate.MODE_NIGHT_NO;
    }

    public static void setDark(Context c, boolean dark) {
        int m = dark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO;
        p(c).edit().putInt(K_NIGHT, m).apply();
        AppCompatDelegate.setDefaultNightMode(m);
    }

    public static void applyNightMode(Context c) {
        AppCompatDelegate.setDefaultNightMode(nightMode(c));
    }

    // Blue is the default accent.
    public static String accent(Context c) {
        return p(c).getString(K_ACCENT, BLUE);
    }

    public static void setAccent(Context c, String accent) {
        p(c).edit().putString(K_ACCENT, accent).apply();
    }

    public static int themeRes(Context c) {
        return GREEN.equals(accent(c)) ? R.style.Theme_Sift_Terminal : R.style.Theme_Sift;
    }

    // Recycle Bin is off by default (deletes are permanent unless enabled).
    public static boolean recycleBin(Context c) {
        return p(c).getBoolean(K_RECYCLE, false);
    }

    public static void setRecycleBin(Context c, boolean on) {
        p(c).edit().putBoolean(K_RECYCLE, on).apply();
    }

    // Last sort mode/direction the user chose; new tabs open with it. Name, ascending by default.
    public static int sortMode(Context c) {
        return p(c).getInt(K_SORT, 0); // Tab.SORT_NAME
    }

    public static boolean sortDesc(Context c) {
        return p(c).getBoolean(K_SORT_DESC, false);
    }

    public static void setSort(Context c, int mode, boolean desc) {
        p(c).edit().putInt(K_SORT, mode).putBoolean(K_SORT_DESC, desc).apply();
    }

    /** Resolve a theme color attribute (e.g. colorPrimary) to an ARGB int. */
    public static int themeColor(Context ctx, int attr) {
        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }
}
