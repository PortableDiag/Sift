package com.sift.explorer.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;

import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.color.DynamicColors;

/** Persists and applies the user's appearance choices: palette and dark/light. */
public class ThemePrefs {

    private static final String FILE = "sift_settings";
    private static final String K_NIGHT = "night";
    private static final String K_PALETTE = "palette";
    /** 1.13 and earlier: "blue" or "green", which were Ocean and Terminal. Read once to migrate. */
    private static final String K_ACCENT = "accent";
    private static final String K_RECYCLE = "recycle_bin";
    private static final String K_SORT = "sort_mode";
    private static final String K_SORT_DESC = "sort_desc";

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // Dark is the default. This is the switch's state; a palette that is one look overrides it.
    public static boolean isDark(Context c) {
        return p(c).getInt(K_NIGHT, AppCompatDelegate.MODE_NIGHT_YES) != AppCompatDelegate.MODE_NIGHT_NO;
    }

    public static void setDark(Context c, boolean dark) {
        int m = dark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO;
        p(c).edit().putInt(K_NIGHT, m).apply();
        applyNightMode(c);
    }

    /** The night mode actually in force: the switch for Material You/Ocean/Terminal, else the palette's. */
    public static int nightMode(Context c) {
        Palettes.Palette pal = palette(c);
        if (!pal.followsMode) return pal.light ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_YES;
        return isDark(c) ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO;
    }

    public static void applyNightMode(Context c) {
        AppCompatDelegate.setDefaultNightMode(nightMode(c));
    }

    /**
     * The saved palette. Saved by name, so adding palettes never shifts a saved one. Material You
     * is the house default; someone upgrading from 1.13 keeps the look they had.
     */
    public static Palettes.Palette palette(Context c) {
        SharedPreferences sp = p(c);
        Palettes.Palette pal = Palettes.byName(sp.getString(K_PALETTE, null));
        if (pal != null) return pal;
        String legacy = sp.getString(K_ACCENT, null);
        String name;
        if ("green".equals(legacy)) name = "Terminal";
        else if (legacy != null || !sp.getAll().isEmpty()) name = "Ocean";
        else name = Palettes.MATERIAL_YOU;
        sp.edit().putString(K_PALETTE, name).remove(K_ACCENT).apply();
        return Palettes.byName(name);
    }

    /** @return false when no house palette has that name. */
    public static boolean setPalette(Context c, String name) {
        Palettes.Palette pal = Palettes.byName(name);
        if (pal == null) return false;
        p(c).edit().putString(K_PALETTE, pal.name).apply();
        applyNightMode(c);
        return true;
    }

    /** Call before super.onCreate: sets the palette's theme, and the wallpaper colours for Material You. */
    public static void applyTheme(Activity a) {
        Palettes.Palette pal = palette(a);
        a.setTheme(pal.style);
        if (pal.isDynamic()) DynamicColors.applyToActivityIfAvailable(a);
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
