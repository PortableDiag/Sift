package com.sift.explorer.fs;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

/** SMB / CIFS share access via jcifs-ng (SMB2/3). Paths are full {@code smb://} URLs. */
public class SmbFileSystem extends FileSystem {

    private final Connection conn;
    private final CIFSContext ctx;
    private final String root;
    private final String host;

    public SmbFileSystem(Connection conn) throws Exception {
        this.conn = conn;

        // Sanitize the host field: strip an accidental scheme / slashes, and allow the
        // user to type "192.168.0.51/NAS" in the host box (share inferred from it).
        String h = conn.host == null ? "" : conn.host.trim();
        h = h.replaceFirst("(?i)^smb:/*", "").replaceAll("^/+", "").replaceAll("/+$", "");
        String shareName = conn.share == null ? "" : conn.share.trim().replaceAll("^/+", "").replaceAll("/+$", "");
        int slash = h.indexOf('/');
        if (slash >= 0) {
            if (shareName.isEmpty()) shareName = h.substring(slash + 1).replaceAll("/+$", "");
            h = h.substring(0, slash);
        }
        this.host = h;

        Properties props = new Properties();
        props.setProperty("jcifs.smb.client.minVersion", "SMB202");
        props.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        props.setProperty("jcifs.smb.client.dfs.disabled", "true");
        // Resolve the host via DNS/IP only. The default order tries NetBIOS broadcast
        // first, which on many LANs mis-resolves a literal IP to 0.0.0.0.
        props.setProperty("jcifs.resolveOrder", "DNS");
        props.setProperty("jcifs.smb.client.responseTimeout", "15000");
        props.setProperty("jcifs.smb.client.soTimeout", "20000");
        props.setProperty("jcifs.smb.client.connTimeout", "8000");
        CIFSContext base = new BaseContext(new PropertyConfiguration(props));
        String user = conn.username == null ? "" : conn.username.trim();
        String pass = conn.password == null ? "" : conn.password;
        String dom = conn.domain == null ? "" : conn.domain;
        if (user.isEmpty() && pass.isEmpty()) {
            // No credentials → guest logon (what `smbclient -N` gets on a "guest ok" share).
            // NOTE: NtlmPasswordAuthenticator("","","") instead does a null-session USER
            // logon, which Samba rejects as "unknown user name or bad password".
            this.ctx = base.withGuestCrendentials();
        } else {
            this.ctx = base.withCredentials(new NtlmPasswordAuthenticator(dom, user, pass));
        }
        StringBuilder r = new StringBuilder("smb://").append(host).append('/');
        if (!shareName.isEmpty()) r.append(shareName).append('/');
        this.root = r.toString();
    }

    @Override public String getId() { return "smb:" + conn.id; }
    @Override public String connectionId() { return conn.id; }
    @Override public String getType() { return "smb"; }
    @Override public String getDisplayName() { return conn.displayName(); }
    @Override public String getRootPath() { return root; }

    private String dirUrl(String path) {
        return path.endsWith("/") ? path : path + "/";
    }

    private SmbFile file(String path) throws Exception {
        return new SmbFile(path, ctx);
    }

    @Override public List<FileItem> list(String path) throws Exception {
        SmbFile dir = new SmbFile(dirUrl(path), ctx);
        SmbFile[] children = dir.listFiles();
        List<FileItem> out = new ArrayList<>(children.length);
        for (SmbFile f : children) {
            String name = f.getName();
            boolean isDir = name.endsWith("/") || f.isDirectory();
            if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
            long size = isDir ? 0 : f.length();
            long mtime = 0;
            try { mtime = f.lastModified(); } catch (Exception ignore) {}
            out.add(new FileItem(this, stripSlash(f.getURL().toString()), name, isDir, false, size, mtime, null));
        }
        return out;
    }

    private static String stripSlash(String url) {
        return url.length() > 1 && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override public FileItem stat(String path) throws Exception {
        SmbFile f = file(path);
        boolean isDir = f.isDirectory();
        return new FileItem(this, stripSlash(path), nameOf(stripSlash(path)), isDir, false,
                isDir ? 0 : f.length(), safeMtime(f), null);
    }

    private static long safeMtime(SmbFile f) {
        try { return f.lastModified(); } catch (Exception e) { return 0; }
    }

    @Override public boolean exists(String path) {
        try { return file(path).exists(); } catch (Exception e) { return false; }
    }

    @Override public void mkdirs(String path) throws Exception { new SmbFile(dirUrl(path), ctx).mkdirs(); }

    @Override public void createFile(String path) throws Exception { file(path).createNewFile(); }

    @Override public void delete(FileItem item) throws Exception {
        SmbFile f = new SmbFile(item.isDirectory ? dirUrl(item.path) : item.path, ctx);
        f.delete();
    }

    @Override public void rename(FileItem item, String newName) throws Exception {
        String parent = getParent(item.path);
        SmbFile src = new SmbFile(item.isDirectory ? dirUrl(item.path) : item.path, ctx);
        SmbFile dst = new SmbFile(childPath(parent, newName) + (item.isDirectory ? "/" : ""), ctx);
        src.renameTo(dst);
    }

    @Override public boolean move(FileItem item, String destDir) throws Exception {
        SmbFile src = new SmbFile(item.isDirectory ? dirUrl(item.path) : item.path, ctx);
        SmbFile dst = new SmbFile(childPath(destDir, item.name) + (item.isDirectory ? "/" : ""), ctx);
        src.renameTo(dst);
        return true;
    }

    @Override public InputStream read(FileItem item) throws Exception { return file(item.path).getInputStream(); }

    @Override public OutputStream write(String path, long sizeHint) throws Exception {
        return new SmbFile(path, ctx).getOutputStream();
    }

    @Override public boolean isRoot(String path) {
        return stripSlash(path).equals(stripSlash(root)) || path.equals("smb://" + host);
    }

    @Override public void close() { try { ctx.close(); } catch (Exception ignore) {} }
}
