package com.sift.explorer.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

/** Remembers the user's preferred external app per file-type key (e.g. VIDEO). */
public class DefaultApps {

    private static final String FILE = "sift_defaults";
    private final SharedPreferences prefs;

    public DefaultApps(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public ComponentName get(String typeKey) {
        String s = prefs.getString(typeKey, null);
        if (s == null) return null;
        int i = s.indexOf('/');
        if (i < 0) return null;
        return new ComponentName(s.substring(0, i), s.substring(i + 1));
    }

    public void set(String typeKey, ComponentName cn) {
        prefs.edit().putString(typeKey, cn.getPackageName() + "/" + cn.getClassName()).apply();
    }

    public void clear(String typeKey) {
        prefs.edit().remove(typeKey).apply();
    }
}
