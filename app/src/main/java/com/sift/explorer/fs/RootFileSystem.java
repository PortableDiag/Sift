package com.sift.explorer.fs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Whole-device access through the {@code su} binary. Each operation runs a fresh
 * {@code su -c} invocation, which keeps the implementation stateless and robust
 * across the many su variants in the wild. Requires a rooted device; if {@code su}
 * is missing or denies access, operations throw and the UI surfaces the error.
 */
public class RootFileSystem extends FileSystem {

    @Override public String getId() { return "root"; }
    @Override public String getType() { return "root"; }
    @Override public String getDisplayName() { return "Root (/)"; }
    @Override public String getRootPath() { return "/"; }

    public static boolean isAvailable() {
        try {
            Process p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            drain(p.getInputStream(), out);
            int code = p.waitFor();
            return code == 0 && out.toString().contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    /** Single-quote a string for POSIX shell. */
    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private Process su(String cmd) throws IOException {
        return new ProcessBuilder("su", "-c", cmd).start();
    }

    private String runCapture(String cmd) throws Exception {
        Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        drain(p.getInputStream(), out);
        int code = p.waitFor();
        String s = out.toString("UTF-8");
        if (code != 0) throw new IOException(s.trim().isEmpty() ? "su exited " + code : s.trim());
        return s;
    }

    private void runCheck(String cmd) throws Exception {
        runCapture(cmd);
    }

    @Override public List<FileItem> list(String path) throws Exception {
        // For each entry emit: <isDir 0/1>|<perms>|<size>|<mtimeEpoch>|<type>|<name>
        String script = "cd " + q(path) + " || exit 1; " +
                "for n in * .* ; do " +
                "[ \"$n\" = '.' ] && continue; [ \"$n\" = '..' ] && continue; " +
                "[ -e \"$n\" ] || [ -L \"$n\" ] || continue; " +
                "if [ -d \"$n\" ]; then d=1; else d=0; fi; " +
                "stat -c \"$d|%A|%s|%Y|%F|%n\" \"$n\" 2>/dev/null; " +
                "done";
        String out = runCapture(script);
        List<FileItem> result = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\|", 6);
            if (parts.length < 6) continue;
            boolean isDir = "1".equals(parts[0]);
            String perms = parts[1];
            long size = parseLong(parts[2]);
            long mtime = parseLong(parts[3]) * 1000L;
            boolean symlink = parts[4].contains("symbolic link");
            String name = parts[5];
            result.add(new FileItem(this, childPath(path, name), name, isDir, symlink, size, mtime, perms));
        }
        return result;
    }

    @Override public FileItem stat(String path) throws Exception {
        String out = runCapture("if [ -d " + q(path) + " ]; then d=1; else d=0; fi; " +
                "stat -c \"$d|%A|%s|%Y|%F\" " + q(path));
        String[] p = out.trim().split("\\|", 5);
        boolean isDir = p.length > 0 && "1".equals(p[0]);
        String perms = p.length > 1 ? p[1] : null;
        long size = p.length > 2 ? parseLong(p[2]) : 0;
        long mtime = p.length > 3 ? parseLong(p[3]) * 1000L : 0;
        boolean symlink = p.length > 4 && p[4].contains("symbolic link");
        return new FileItem(this, path, nameOf(path), isDir, symlink, size, mtime, perms);
    }

    @Override public boolean exists(String path) {
        try { runCheck("[ -e " + q(path) + " ] || [ -L " + q(path) + " ]"); return true; }
        catch (Exception e) { return false; }
    }

    @Override public void mkdirs(String path) throws Exception { runCheck("mkdir -p " + q(path)); }

    @Override public void createFile(String path) throws Exception { runCheck("touch " + q(path)); }

    @Override public void delete(FileItem item) throws Exception { runCheck("rm -rf " + q(item.path)); }

    @Override public void rename(FileItem item, String newName) throws Exception {
        runCheck("mv -f " + q(item.path) + " " + q(childPath(getParent(item.path), newName)));
    }

    @Override public boolean move(FileItem item, String destDir) throws Exception {
        runCheck("mv -f " + q(item.path) + " " + q(childPath(destDir, item.name)));
        return true;
    }

    @Override public InputStream read(FileItem item) throws Exception {
        final Process p = su("cat " + q(item.path));
        final InputStream in = p.getInputStream();
        return new InputStream() {
            @Override public int read() throws IOException { return in.read(); }
            @Override public int read(byte[] b, int off, int len) throws IOException { return in.read(b, off, len); }
            @Override public void close() throws IOException {
                in.close();
                try { p.waitFor(); } catch (InterruptedException ignore) {}
                p.destroy();
            }
        };
    }

    @Override public OutputStream write(String path, long sizeHint) throws Exception {
        final Process p = su("cat > " + q(path));
        final OutputStream os = p.getOutputStream();
        return new OutputStream() {
            @Override public void write(int b) throws IOException { os.write(b); }
            @Override public void write(byte[] b, int off, int len) throws IOException { os.write(b, off, len); }
            @Override public void flush() throws IOException { os.flush(); }
            @Override public void close() throws IOException {
                os.flush(); os.close();
                try { p.waitFor(); } catch (InterruptedException ignore) {}
                p.destroy();
            }
        };
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    private static void drain(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }
}
