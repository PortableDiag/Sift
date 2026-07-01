package com.sift.explorer.fs;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** A saved network location (SMB share, SFTP server, or FTP/FTPS server). */
public class Connection {

    public static final String TYPE_SMB = "smb";
    public static final String TYPE_SFTP = "sftp";
    public static final String TYPE_FTP = "ftp";
    public static final String TYPE_FTPS = "ftps";

    /** True for any remote backend backed by a saved connection. */
    public static boolean isNetwork(String type) {
        return TYPE_SMB.equals(type) || TYPE_SFTP.equals(type)
                || TYPE_FTP.equals(type) || TYPE_FTPS.equals(type);
    }

    public String id;
    public String type;
    public String name;
    public String host;
    public int port;
    public String username;
    public String password;
    public String domain;       // SMB workgroup/domain (optional)
    public String share;        // SMB share name (optional; can also be in path)
    public String privateKey;   // SFTP PEM key material (optional)
    public String initialPath;  // optional starting path

    public Connection() {
        this.id = UUID.randomUUID().toString();
    }

    public int defaultPort() {
        if (TYPE_SFTP.equals(type)) return 22;
        if (TYPE_FTP.equals(type) || TYPE_FTPS.equals(type)) return 21;
        return 445;
    }

    public int effectivePort() {
        return port > 0 ? port : defaultPort();
    }

    public String displayName() {
        if (name != null && !name.trim().isEmpty()) return name.trim();
        String u = (username != null && !username.isEmpty()) ? username + "@" : "";
        return type + "://" + u + host + (share != null && !share.isEmpty() ? "/" + share : "");
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("type", type);
        o.put("name", name);
        o.put("host", host);
        o.put("port", port);
        o.put("username", username);
        o.put("password", password);
        o.put("domain", domain);
        o.put("share", share);
        o.put("privateKey", privateKey);
        o.put("initialPath", initialPath);
        return o;
    }

    public static Connection fromJson(JSONObject o) {
        Connection c = new Connection();
        c.id = o.optString("id", c.id);
        c.type = o.optString("type", TYPE_SMB);
        c.name = o.optString("name", null);
        c.host = o.optString("host", null);
        c.port = o.optInt("port", 0);
        c.username = o.optString("username", null);
        c.password = o.optString("password", null);
        c.domain = o.optString("domain", null);
        c.share = o.optString("share", null);
        c.privateKey = o.optString("privateKey", null);
        c.initialPath = o.optString("initialPath", null);
        return c;
    }
}
