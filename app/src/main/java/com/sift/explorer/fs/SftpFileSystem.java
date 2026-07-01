package com.sift.explorer.fs;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Vector;

/** SSH/SFTP access via the (maintained) JSch fork. Paths are absolute POSIX paths. */
public class SftpFileSystem extends FileSystem {

    private final Connection conn;
    private final JSch jsch = new JSch();
    private Session session;
    private ChannelSftp meta;     // shared metadata channel (synchronized access)
    private final String root;

    public SftpFileSystem(Connection conn) throws Exception {
        this.conn = conn;
        if (conn.privateKey != null && !conn.privateKey.trim().isEmpty()) {
            jsch.addIdentity("sift", conn.privateKey.getBytes(), null,
                    conn.password == null ? null : conn.password.getBytes());
        }
        String start = conn.initialPath;
        this.root = (start != null && !start.isEmpty()) ? start : ".";
    }

    @Override public String getId() { return "sftp:" + conn.id; }
    @Override public String connectionId() { return conn.id; }
    @Override public String getType() { return "sftp"; }
    @Override public String getDisplayName() { return conn.displayName(); }
    @Override public String getRootPath() { return root; }

    private synchronized Session session() throws Exception {
        if (session != null && session.isConnected()) return session;
        session = jsch.getSession(conn.username, conn.host, conn.effectivePort());
        if (conn.password != null && !conn.password.isEmpty()) session.setPassword(conn.password);
        Properties cfg = new Properties();
        cfg.put("StrictHostKeyChecking", "no");
        cfg.put("PreferredAuthentications", "publickey,password,keyboard-interactive");
        session.setConfig(cfg);
        session.setTimeout(20000);
        session.connect(15000);
        return session;
    }

    private synchronized ChannelSftp meta() throws Exception {
        if (meta != null && meta.isConnected()) return meta;
        meta = (ChannelSftp) session().openChannel("sftp");
        meta.connect(15000);
        return meta;
    }

    private ChannelSftp openChannel() throws Exception {
        ChannelSftp c = (ChannelSftp) session().openChannel("sftp");
        c.connect(15000);
        return c;
    }

    @Override public synchronized List<FileItem> list(String path) throws Exception {
        ChannelSftp c = meta();
        @SuppressWarnings("unchecked")
        Vector<ChannelSftp.LsEntry> entries = c.ls(path);
        List<FileItem> out = new ArrayList<>();
        for (ChannelSftp.LsEntry e : entries) {
            String name = e.getFilename();
            if (name.equals(".") || name.equals("..")) continue;
            SftpATTRS a = e.getAttrs();
            boolean link = a.isLink();
            boolean isDir = a.isDir();
            String full = childPath(path, name);
            if (link) {
                try { SftpATTRS real = c.stat(full); isDir = real.isDir(); } catch (Exception ignore) {}
            }
            out.add(new FileItem(this, full, name, isDir, link,
                    a.getSize(), a.getMTime() * 1000L, a.getPermissionsString()));
        }
        return out;
    }

    @Override public synchronized FileItem stat(String path) throws Exception {
        SftpATTRS a = meta().stat(path);
        return new FileItem(this, path, nameOf(path), a.isDir(), a.isLink(),
                a.getSize(), a.getMTime() * 1000L, a.getPermissionsString());
    }

    @Override public synchronized boolean exists(String path) {
        try { meta().stat(path); return true; } catch (Exception e) { return false; }
    }

    @Override public synchronized void mkdirs(String path) throws Exception {
        ChannelSftp c = meta();
        if (statQuiet(c, path) != null) return;
        String parent = getParent(path);
        if (parent != null && !parent.equals(path) && statQuiet(c, parent) == null) mkdirs(parent);
        c.mkdir(path);
    }

    private SftpATTRS statQuiet(ChannelSftp c, String path) {
        try { return c.stat(path); } catch (Exception e) { return null; }
    }

    @Override public synchronized void createFile(String path) throws Exception {
        OutputStream os = meta().put(path);
        os.close();
    }

    @Override public synchronized void delete(FileItem item) throws Exception {
        deleteRecursive(meta(), item.path, item.isDirectory);
    }

    private void deleteRecursive(ChannelSftp c, String path, boolean isDir) throws Exception {
        if (isDir) {
            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> kids = c.ls(path);
            for (ChannelSftp.LsEntry e : kids) {
                String n = e.getFilename();
                if (n.equals(".") || n.equals("..")) continue;
                deleteRecursive(c, childPath(path, n), e.getAttrs().isDir() && !e.getAttrs().isLink());
            }
            c.rmdir(path);
        } else {
            c.rm(path);
        }
    }

    @Override public synchronized void rename(FileItem item, String newName) throws Exception {
        meta().rename(item.path, childPath(getParent(item.path), newName));
    }

    @Override public synchronized boolean move(FileItem item, String destDir) throws Exception {
        meta().rename(item.path, childPath(destDir, item.name));
        return true;
    }

    @Override public InputStream read(FileItem item) throws Exception {
        final ChannelSftp c = openChannel();
        final InputStream in = c.get(item.path);
        return new java.io.FilterInputStream(in) {
            @Override public void close() throws java.io.IOException { super.close(); c.disconnect(); }
        };
    }

    @Override public OutputStream write(String path, long sizeHint) throws Exception {
        final ChannelSftp c = openChannel();
        final OutputStream os = c.put(path);
        return new java.io.FilterOutputStream(os) {
            @Override public void write(byte[] b, int off, int len) throws java.io.IOException { out.write(b, off, len); }
            @Override public void close() throws java.io.IOException { super.close(); c.disconnect(); }
        };
    }

    @Override public void close() {
        try { if (meta != null) meta.disconnect(); } catch (Exception ignore) {}
        try { if (session != null) session.disconnect(); } catch (Exception ignore) {}
    }
}
