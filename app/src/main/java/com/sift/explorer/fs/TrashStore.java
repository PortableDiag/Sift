package com.sift.explorer.fs;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Index of items currently in the Recycle Bin (most-recently-deleted first). */
public class TrashStore {

    private static final String FILE = "sift_trash";
    private static final String KEY = "items";

    private final SharedPreferences prefs;

    public TrashStore(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public synchronized List<TrashEntry> getAll() {
        List<TrashEntry> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(TrashEntry.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignore) {}
        Collections.sort(out, new Comparator<TrashEntry>() {
            @Override public int compare(TrashEntry a, TrashEntry b) {
                return Long.compare(b.deletedAt, a.deletedAt);
            }
        });
        return out;
    }

    public synchronized int count() { return getAll().size(); }

    public synchronized void add(TrashEntry e) {
        List<TrashEntry> all = getAll();
        all.add(e);
        writeAll(all);
    }

    public synchronized void remove(String id) {
        List<TrashEntry> all = getAll();
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).id.equals(id)) all.remove(i);
        writeAll(all);
    }

    private void writeAll(List<TrashEntry> all) {
        try {
            JSONArray arr = new JSONArray();
            for (TrashEntry e : all) arr.put(e.toJson());
            prefs.edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignore) {}
    }
}
