package com.sift.explorer.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.List;

/**
 * Everything the API can ask of the app. {@link ApiRouter} decides which request means which
 * call and what is malformed; this decides what the call does. No Android types cross it, so the
 * router is tested against a fake.
 *
 * <p>A location is always a filesystem spec plus a path: {@code fs} is {@code "local"} (the
 * default — internal storage or an SD card, picked by the path), {@code "root"}, or a saved
 * connection's id. A null path means that filesystem's root.
 *
 * <p>Methods throw {@link ApiException} for anything that is the caller's fault, and any other
 * exception for what the storage said (it becomes a 502 with the backend's message).
 */
public interface ApiBackend {

    JSONObject instance() throws Exception;

    JSONArray locations() throws Exception;

    // ---- connections --------------------------------------------------------

    JSONArray connections() throws Exception;

    JSONObject connection(String id) throws Exception;

    JSONObject saveConnection(String id, JSONObject spec) throws Exception;

    void deleteConnection(String id) throws Exception;

    // ---- files ------------------------------------------------------------------

    final class ListOptions {
        public boolean hidden;
        public String sort = "name";
        public boolean desc;
        public boolean foldersFirst = true;
        public String filter = "";
        public int limit = 0;
        public int offset = 0;
    }

    JSONObject list(String fs, String path, ListOptions o) throws Exception;

    JSONObject stat(String fs, String path) throws Exception;

    final class Download {
        public final InputStream in;
        public final long length;
        public final String name, mime;

        public Download(InputStream in, long length, String name, String mime) {
            this.in = in; this.length = length; this.name = name; this.mime = mime;
        }
    }

    Download read(String fs, String path) throws Exception;

    JSONObject write(String fs, String path, InputStream body, long length, boolean overwrite) throws Exception;

    JSONObject mkdir(String fs, String path) throws Exception;

    JSONObject createFile(String fs, String path) throws Exception;

    JSONObject rename(String fs, String path, String newName) throws Exception;

    /** @param permanent null follows the Recycle Bin setting, as tapping Delete does. */
    JSONObject delete(String fs, List<String> paths, Boolean permanent) throws Exception;

    /** Copy, move, zip or extract — a job. {@code waitMs} > 0 blocks up to that long for it. */
    JSONObject transfer(String kind, String fs, List<String> paths, String destFs, String dest, long waitMs) throws Exception;

    JSONObject properties(String fs, List<String> paths) throws Exception;

    JSONObject diskUsage(String fs, String path, int limit) throws Exception;

    // ---- jobs -------------------------------------------------------------------

    JSONArray jobs();

    JSONObject job(String id);

    JSONObject cancelJob(String id);

    // ---- bookmarks and the Recycle Bin -----------------------------------------

    JSONArray bookmarks();

    JSONObject addBookmark(String fs, String path, String label) throws Exception;

    boolean removeBookmark(String fs, String path) throws Exception;

    JSONArray trash();

    JSONObject restoreTrash(String id) throws Exception;

    JSONObject purgeTrash(String id) throws Exception;

    JSONObject emptyTrash() throws Exception;

    // ---- the screen -------------------------------------------------------------

    JSONObject tabs();

    JSONObject openTab(String fs, String path) throws Exception;

    JSONObject updateTab(int id, String path, Boolean current) throws Exception;

    boolean closeTab(int id);

    /** @param how default (as a tap), picker (Open with…), text (open as text), external. */
    JSONObject open(String fs, String path, String how) throws Exception;

    JSONObject share(String fs, List<String> paths) throws Exception;

    JSONObject popOut(String fs, String path) throws Exception;

    // ---- settings ---------------------------------------------------------------

    JSONObject settings();

    JSONObject updateSettings(JSONObject patch) throws Exception;

    JSONArray palettes();

    JSONArray defaultApps();

    boolean clearDefaultApp(String type);
}
