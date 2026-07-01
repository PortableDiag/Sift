package com.sift.explorer.fs;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.apache.commons.net.util.TrustManagerUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain FTP and explicit FTPS (FTP-over-TLS) via Apache Commons Net. Paths are
 * absolute POSIX paths. A single control connection ({@link #meta}) serves the
 * metadata operations; each read/write stream gets its own connection so a
 * transfer never blocks listings (an FTP control channel does one thing at a time).
 */
public class FtpFileSystem extends FileSystem {

    private final Connection conn;
    private final boolean secure;   // FTPS (explicit TLS) when true
    private final String root;
    private FTPClient meta;

    public FtpFileSystem(Connection conn) {
        this.conn = conn;
        this.secure = Connection.TYPE_FTPS.equals(conn.type);
        String start = conn.initialPath;
        this.root = (start != null && !start.isEmpty()) ? start : "/";
    }

    @Override public String getId() { return conn.type + ":" + conn.id; }
    @Override public String connectionId() { return conn.id; }
    @Override public String getType() { return conn.type; }
    @Override public String getDisplayName() { return conn.displayName(); }
    @Override public String getRootPath() { return root; }

    /** Opens and authenticates a fresh client. Caller owns it (see {@link #safeClose}). */
    private FTPClient connect() throws Exception {
        FTPClient c;
        if (secure) {
            FTPSClient s = new FTPSClient(false); // explicit TLS
            // Match the app's lenient posture (cf. SFTP's StrictHostKeyChecking=no):
            // accept self-signed certs so home/NAS FTPS servers are usable.
            s.setTrustManager(TrustManagerUtils.getAcceptAllTrustManager());
            c = s;
        } else {
            c = new FTPClient();
        }
        c.setConnectTimeout(15000);
        c.setDefaultTimeout(20000);
        c.connect(conn.host, conn.effectivePort());
        if (!FTPReply.isPositiveCompletion(c.getReplyCode())) {
            int reply = c.getReplyCode();
            safeClose(c);
            throw new Exception("FTP server refused connection (" + reply + ")");
        }
        String user = (conn.username == null || conn.username.isEmpty()) ? "anonymous" : conn.username;
        String pass = conn.password == null ? "" : conn.password;
        if (!c.login(user, pass)) {
            String msg = c.getReplyString();
            safeClose(c);
            throw new Exception("Login failed" + (msg == null ? "" : ": " + msg.trim()));
        }
        if (secure) {
            ((FTPSClient) c).execPBSZ(0);
            ((FTPSClient) c).execPROT("P"); // encrypt the data channel too
        }
        c.setFileType(FTP.BINARY_FILE_TYPE);
        c.enterLocalPassiveMode();
        c.setControlKeepAliveTimeout(java.time.Duration.ofSeconds(30)); // NOOP during long transfers
        return c;
    }

    private synchronized FTPClient meta() throws Exception {
        if (meta != null && meta.isConnected()) return meta;
        meta = connect();
        return meta;
    }

    @Override public synchronized List<FileItem> list(String path) throws Exception {
        FTPClient c = meta();
        FTPFile[] files = c.listFiles(path);
        List<FileItem> out = new ArrayList<>();
        if (files != null) {
            for (FTPFile f : files) {
                if (f == null || f.getName() == null) continue;
                String name = f.getName();
                if (name.equals(".") || name.equals("..")) continue;
                out.add(toItem(childPath(path, name), name, f));
            }
        }
        return out;
    }

    @Override public synchronized FileItem stat(String path) throws Exception {
        String parent = getParent(path);
        if (parent == null) { // navigable root: assume a directory
            return new FileItem(this, path, nameOf(path), true, false, 0, 0, null);
        }
        FTPClient c = meta();
        FTPFile[] siblings = c.listFiles(parent);
        String want = nameOf(path);
        if (siblings != null) {
            for (FTPFile f : siblings) {
                if (f != null && want.equals(f.getName())) return toItem(path, want, f);
            }
        }
        throw new Exception("Not found: " + path);
    }

    private FileItem toItem(String full, String name, FTPFile f) {
        long mtime = f.getTimestamp() != null ? f.getTimestamp().getTimeInMillis() : 0L;
        return new FileItem(this, full, name, f.isDirectory(), f.isSymbolicLink(),
                f.getSize(), mtime, permString(f));
    }

    @Override public synchronized boolean exists(String path) {
        try { stat(path); return true; } catch (Exception e) { return false; }
    }

    @Override public synchronized void mkdirs(String path) throws Exception {
        FTPClient c = meta();
        String parent = getParent(path);
        if (parent != null && !parent.equals(path) && !c.changeWorkingDirectory(parent)) {
            mkdirs(parent);
        }
        if (!c.changeWorkingDirectory(path)) {
            if (!c.makeDirectory(path) && !c.changeWorkingDirectory(path)) {
                throw new Exception("mkdir failed: " + c.getReplyString());
            }
        }
    }

    @Override public synchronized void createFile(String path) throws Exception {
        FTPClient c = meta();
        if (!c.storeFile(path, new java.io.ByteArrayInputStream(new byte[0]))) {
            throw new Exception("Create failed: " + c.getReplyString());
        }
    }

    @Override public synchronized void delete(FileItem item) throws Exception {
        deleteRecursive(meta(), item.path, item.isDirectory);
    }

    private void deleteRecursive(FTPClient c, String path, boolean isDir) throws Exception {
        if (isDir) {
            FTPFile[] kids = c.listFiles(path);
            if (kids != null) {
                for (FTPFile e : kids) {
                    if (e == null || e.getName() == null) continue;
                    String n = e.getName();
                    if (n.equals(".") || n.equals("..")) continue;
                    deleteRecursive(c, childPath(path, n), e.isDirectory() && !e.isSymbolicLink());
                }
            }
            if (!c.removeDirectory(path)) throw new Exception("rmdir failed: " + c.getReplyString());
        } else {
            if (!c.deleteFile(path)) throw new Exception("delete failed: " + c.getReplyString());
        }
    }

    @Override public synchronized void rename(FileItem item, String newName) throws Exception {
        if (!meta().rename(item.path, childPath(getParent(item.path), newName))) {
            throw new Exception("Rename failed: " + meta.getReplyString());
        }
    }

    @Override public synchronized boolean move(FileItem item, String destDir) throws Exception {
        // Server-side rename; returns false when the server won't (caller falls back to copy+delete).
        return meta().rename(item.path, childPath(destDir, item.name));
    }

    @Override public InputStream read(FileItem item) throws Exception {
        final FTPClient c = connect();
        final InputStream in = c.retrieveFileStream(item.path);
        if (in == null) { String m = c.getReplyString(); safeClose(c); throw new Exception("Cannot read: " + m); }
        return new java.io.FilterInputStream(in) {
            @Override public void close() throws java.io.IOException {
                super.close();
                try { c.completePendingCommand(); } catch (Exception ignore) {}
                safeClose(c);
            }
        };
    }

    @Override public OutputStream write(String path, long sizeHint) throws Exception {
        final FTPClient c = connect();
        final OutputStream os = c.storeFileStream(path);
        if (os == null) { String m = c.getReplyString(); safeClose(c); throw new Exception("Cannot write: " + m); }
        return new java.io.FilterOutputStream(os) {
            @Override public void write(byte[] b, int off, int len) throws java.io.IOException { out.write(b, off, len); }
            @Override public void close() throws java.io.IOException {
                super.close();
                try { c.completePendingCommand(); } catch (Exception ignore) {}
                safeClose(c);
            }
        };
    }

    @Override public synchronized void close() {
        safeClose(meta);
        meta = null;
    }

    private static void safeClose(FTPClient c) {
        if (c == null) return;
        try { if (c.isConnected()) { c.logout(); } } catch (Exception ignore) {}
        try { if (c.isConnected()) { c.disconnect(); } } catch (Exception ignore) {}
    }

    /** Render an ls-style "drwxr-xr-x" string from the parsed FTP permissions. */
    private static String permString(FTPFile f) {
        StringBuilder sb = new StringBuilder(10);
        sb.append(f.isDirectory() ? 'd' : (f.isSymbolicLink() ? 'l' : '-'));
        int[] who = {FTPFile.USER_ACCESS, FTPFile.GROUP_ACCESS, FTPFile.WORLD_ACCESS};
        for (int w : who) {
            sb.append(f.hasPermission(w, FTPFile.READ_PERMISSION) ? 'r' : '-');
            sb.append(f.hasPermission(w, FTPFile.WRITE_PERMISSION) ? 'w' : '-');
            sb.append(f.hasPermission(w, FTPFile.EXECUTE_PERMISSION) ? 'x' : '-');
        }
        return sb.toString();
    }
}
