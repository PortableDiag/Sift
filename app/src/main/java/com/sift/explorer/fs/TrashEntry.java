package com.sift.explorer.fs;

import org.json.JSONException;
import org.json.JSONObject;

/** One item sitting in the Recycle Bin, with everything needed to restore it. */
public class TrashEntry {

    public String id;
    public String fsType;        // local | root | smb | sftp
    public String connectionId;  // for smb/sftp, else null
    public String name;
    public boolean isDirectory;
    public String originalParent;
    public String trashDir;      // the per-item folder inside .SiftTrash holding it
    public long deletedAt;

    public TrashEntry() {}

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("fsType", fsType);
        o.put("connectionId", connectionId);
        o.put("name", name);
        o.put("isDirectory", isDirectory);
        o.put("originalParent", originalParent);
        o.put("trashDir", trashDir);
        o.put("deletedAt", deletedAt);
        return o;
    }

    public static TrashEntry fromJson(JSONObject o) {
        TrashEntry e = new TrashEntry();
        e.id = o.optString("id");
        e.fsType = o.optString("fsType", "local");
        e.connectionId = o.has("connectionId") && !o.isNull("connectionId") ? o.optString("connectionId") : null;
        e.name = o.optString("name");
        e.isDirectory = o.optBoolean("isDirectory");
        e.originalParent = o.optString("originalParent");
        e.trashDir = o.optString("trashDir");
        e.deletedAt = o.optLong("deletedAt");
        return e;
    }
}
