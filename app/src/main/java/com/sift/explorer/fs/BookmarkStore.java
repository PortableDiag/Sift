package com.sift.explorer.fs;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Persists bookmarks; always returns them sorted alphabetically by label. */
public class BookmarkStore {

    private static final String FILE = "sift_bookmarks";
    private static final String KEY = "items";

    private final SharedPreferences prefs;

    public BookmarkStore(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public synchronized List<Bookmark> getAll() {
        List<Bookmark> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(Bookmark.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignore) {}
        Collections.sort(out, new Comparator<Bookmark>() {
            @Override public int compare(Bookmark a, Bookmark b) {
                int c = a.label.compareToIgnoreCase(b.label);
                return c != 0 ? c : a.path.compareToIgnoreCase(b.path);
            }
        });
        return out;
    }

    public synchronized boolean contains(String type, String path, String connectionId) {
        for (Bookmark b : getAll()) if (b.sameTarget(type, path, connectionId)) return true;
        return false;
    }

    public synchronized void add(Bookmark bm) {
        List<Bookmark> all = getAll();
        for (Bookmark b : all) if (b.sameTarget(bm.type, bm.path, bm.connectionId)) return; // de-dupe
        all.add(bm);
        writeAll(all);
    }

    public synchronized void remove(String type, String path, String connectionId) {
        List<Bookmark> all = getAll();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).sameTarget(type, path, connectionId)) all.remove(i);
        }
        writeAll(all);
    }

    private void writeAll(List<Bookmark> all) {
        try {
            JSONArray arr = new JSONArray();
            for (Bookmark b : all) arr.put(b.toJson());
            prefs.edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignore) {}
    }
}
