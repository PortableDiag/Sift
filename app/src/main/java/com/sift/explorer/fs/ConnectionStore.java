package com.sift.explorer.fs;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists saved network connections in an AES-256 EncryptedSharedPreferences
 * file so credentials never touch plaintext on disk. Falls back to regular
 * prefs only if the keystore is unavailable (very old/broken devices).
 */
public class ConnectionStore {

    private static final String FILE = "sift_connections_secure";
    private static final String KEY = "connections";

    private final SharedPreferences prefs;

    public ConnectionStore(Context ctx) {
        prefs = open(ctx.getApplicationContext());
    }

    private static SharedPreferences open(Context ctx) {
        try {
            MasterKey key = new MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    ctx, FILE, key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            return ctx.getSharedPreferences(FILE + "_plain", Context.MODE_PRIVATE);
        }
    }

    public synchronized List<Connection> getAll() {
        List<Connection> out = new ArrayList<>();
        try {
            String raw = prefs.getString(KEY, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                out.add(Connection.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignore) {}
        return out;
    }

    public synchronized void save(Connection c) {
        List<Connection> all = getAll();
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(c.id)) { all.set(i, c); replaced = true; break; }
        }
        if (!replaced) all.add(c);
        writeAll(all);
    }

    public synchronized void delete(String id) {
        List<Connection> all = getAll();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).id.equals(id)) all.remove(i);
        }
        writeAll(all);
    }

    public Connection find(String id) {
        for (Connection c : getAll()) if (c.id.equals(id)) return c;
        return null;
    }

    private void writeAll(List<Connection> all) {
        try {
            JSONArray arr = new JSONArray();
            for (Connection c : all) arr.put(c.toJson());
            prefs.edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignore) {}
    }
}
