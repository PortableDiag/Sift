package com.sift.explorer.fs;

import org.json.JSONException;
import org.json.JSONObject;

/** A saved shortcut to a folder on any backend. */
public class Bookmark {

    public String label;
    public String type;          // local | root | smb | sftp
    public String path;
    public String connectionId;  // for smb/sftp, else null

    public Bookmark() {}

    public Bookmark(String label, String type, String path, String connectionId) {
        this.label = label;
        this.type = type;
        this.path = path;
        this.connectionId = connectionId;
    }

    /** Two bookmarks point at the same place when type, path and connection match. */
    public boolean sameTarget(String type, String path, String connectionId) {
        return eq(this.type, type) && eq(this.path, path) && eq(this.connectionId, connectionId);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("label", label);
        o.put("type", type);
        o.put("path", path);
        o.put("connectionId", connectionId);
        return o;
    }

    public static Bookmark fromJson(JSONObject o) {
        Bookmark b = new Bookmark();
        b.label = o.optString("label", "");
        b.type = o.optString("type", "local");
        b.path = o.optString("path", "/");
        b.connectionId = o.has("connectionId") && !o.isNull("connectionId") ? o.optString("connectionId") : null;
        return b;
    }
}
