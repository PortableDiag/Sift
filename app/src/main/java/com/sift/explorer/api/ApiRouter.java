package com.sift.explorer.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The API's rules: which path means what, what each accepts, and which failures are the caller's
 * fault rather than the app's. No Android types reach this file, so the whole surface is unit
 * tested against a fake {@link ApiBackend}.
 *
 * <p>Two conventions run through all of it, as in Lull:
 * <ul>
 *   <li><b>Every endpoint is something the UI can already do.</b> A second pair of hands, not a
 *       second set of features.</li>
 *   <li><b>A wrong request gets a status and a sentence</b>, never a stack trace and never a
 *       silent 200. An agent's only way to tell what it got wrong is what comes back.</li>
 * </ul>
 */
public class ApiRouter {

    /** Bumped when a response shape changes in a way a caller could notice. */
    public static final int API_VERSION = 1;

    /** Answered without a key: liveness, so a watchdog needs no credential. */
    private static final Set<String> OPEN_PATHS = Collections.singleton("/api/health");

    static final Set<String> SORTS = set("name", "size", "date", "type");
    static final Set<String> OPEN_HOW = set("default", "picker", "text", "external");

    /** A copy that has not finished in this long comes back as a running job instead. */
    static final long MAX_WAIT_MS = 120_000;

    private final ApiBackend backend;
    private final Supplier<String> expectedKey;

    public ApiRouter(ApiBackend backend, Supplier<String> expectedKey) {
        this.backend = backend;
        this.expectedKey = expectedKey;
    }

    public ApiResponse handle(ApiRequest r) {
        try {
            if (r.method.equals("OPTIONS")) return ApiResponse.noContent();
            if (!r.path.equals("/api") && !r.path.startsWith("/api/")) {
                return ApiResponse.error(404, "not an API path",
                        "everything this server answers lives under /api — GET /api/docs lists it");
            }
            if (!OPEN_PATHS.contains(r.path) && !authorised(r)) {
                return ApiResponse.error(401, "missing or invalid API key",
                        "send it as the X-API-Key header, or Authorization: Bearer <key>");
            }
            return dispatch(r);
        } catch (ApiException e) {
            return ApiResponse.error(e.status, e.getMessage(), e.hint);
        } catch (FileNotFoundException e) {
            return ApiResponse.error(404, "not found: " + e.getMessage(), null);
        } catch (SecurityException e) {
            return ApiResponse.error(403, "Android refused: " + e.getMessage(),
                    "Sift needs All files access for local storage — grant it in Sift on the phone");
        } catch (Exception e) {
            return ApiResponse.error(500, e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
    }

    /**
     * Constant-time comparison: the timing of a string compare leaks how much of a key is right,
     * and a local socket is exactly where an attacker can afford the millions of tries.
     */
    private boolean authorised(ApiRequest r) {
        String expected = expectedKey.get();
        if (expected == null || expected.isEmpty()) return false;
        String offered = r.header("x-api-key");
        if (offered == null) {
            String auth = r.header("authorization");
            if (auth == null) return false;
            offered = auth.replaceFirst("(?i)^bearer\\s+", "").trim();
        }
        byte[] a = offered.getBytes(StandardCharsets.UTF_8);
        byte[] b = expected.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }

    // ---- dispatch ---------------------------------------------------------------

    private ApiResponse dispatch(ApiRequest r) throws Exception {
        String rest = r.path.length() > 4 ? r.path.substring(5) : "";
        List<String> seg = rest.isEmpty() ? Collections.emptyList() : Arrays.asList(rest.split("/"));
        String head = seg.isEmpty() ? "" : seg.get(0);
        List<String> tail = seg.size() > 1 ? seg.subList(1, seg.size()) : Collections.emptyList();

        switch (head) {
            case "": return get(r, this::index);
            case "health": return get(r, () -> ApiResponse.json(new JSONObject()
                    .put("ok", true).put("app", "Sift").put("api", API_VERSION)));
            case "docs": return get(r, () -> ApiResponse.text(ApiDocs.MARKDOWN, "text/markdown; charset=utf-8"));
            case "instance": return get(r, () -> ApiResponse.json(backend.instance()));
            case "locations": return get(r, () -> ApiResponse.json(backend.locations()));
            case "connections": return connections(r, tail);
            case "files": return files(r, tail);
            case "disk-usage": return get(r, () -> ApiResponse.json(
                    backend.diskUsage(r.q("fs", "local"), r.q("path"), r.qInt("limit", 50))));
            case "jobs": return jobs(r, tail);
            case "bookmarks": return bookmarks(r);
            case "trash": return trash(r, tail);
            case "tabs": return tabs(r, tail);
            case "open": return post(r, () -> {
                JSONObject b = r.json();
                String how = b.optString("with", "default");
                if (!OPEN_HOW.contains(how)) throw bad("with", how, OPEN_HOW);
                return ApiResponse.json(backend.open(fs(b), ApiRequest.need(b, "path"), how));
            });
            case "share": return post(r, () -> {
                JSONObject b = r.json();
                return ApiResponse.json(backend.share(fs(b), ApiRequest.paths(b)));
            });
            case "popout": return post(r, () -> {
                JSONObject b = r.json();
                return ApiResponse.json(backend.popOut(fs(b), ApiRequest.need(b, "path")));
            });
            case "settings": return settings(r);
            case "palettes": return get(r, () -> ApiResponse.json(backend.palettes()));
            case "default-apps": return defaultApps(r, tail);
            default: return noRoute(r);
        }
    }

    private ApiResponse index() throws Exception {
        return ApiResponse.json(new JSONObject()
                .put("app", "Sift").put("api", API_VERSION).put("docs", "/api/docs")
                .put("endpoints", new JSONArray(ApiDocs.SUMMARY)));
    }

    private ApiResponse connections(ApiRequest r, List<String> tail) throws Exception {
        if (tail.isEmpty()) {
            switch (r.method) {
                case "GET": return ApiResponse.json(backend.connections());
                case "POST": return ApiResponse.json(backend.saveConnection(null, r.json()), 201);
                default: return notAllowed(r, "GET", "POST");
            }
        }
        if (tail.size() != 1) return noRoute(r);
        String id = tail.get(0);
        switch (r.method) {
            case "GET": return ApiResponse.json(backend.connection(id));
            case "PATCH": return ApiResponse.json(backend.saveConnection(id, r.json()));
            case "DELETE": backend.deleteConnection(id); return ok();
            default: return notAllowed(r, "GET", "PATCH", "DELETE");
        }
    }

    private ApiResponse files(ApiRequest r, List<String> tail) throws Exception {
        if (tail.isEmpty()) return get(r, () -> {
            ApiBackend.ListOptions o = new ApiBackend.ListOptions();
            o.hidden = r.qBool("hidden", false);
            o.sort = r.q("sort", "name");
            if (!SORTS.contains(o.sort)) throw bad("sort", o.sort, SORTS);
            o.desc = r.qBool("desc", false);
            o.foldersFirst = r.qBool("folders_first", true);
            o.filter = r.q("q", "");
            o.limit = r.qInt("limit", 0);
            o.offset = r.qInt("offset", 0);
            if (o.limit < 0 || o.offset < 0) throw new ApiException(400, "limit and offset cannot be negative");
            return ApiResponse.json(backend.list(r.q("fs", "local"), r.q("path"), o));
        });
        if (tail.size() != 1) return noRoute(r);
        switch (tail.get(0)) {
            case "stat": return get(r, () -> ApiResponse.json(backend.stat(r.q("fs", "local"), needQ(r, "path"))));
            case "content":
                if (r.method.equals("GET")) {
                    ApiBackend.Download d = backend.read(r.q("fs", "local"), needQ(r, "path"));
                    return ApiResponse.stream(d.in, d.mime, d.length, d.name, r.qBool("inline", false));
                }
                if (r.method.equals("PUT")) {
                    JSONObject out = backend.write(r.q("fs", "local"), needQ(r, "path"), r.stream(),
                            r.contentLength, r.qBool("overwrite", false));
                    return ApiResponse.json(out, 201);
                }
                return notAllowed(r, "GET", "PUT");
            case "mkdir": return post(r, () -> {
                JSONObject b = r.json();
                return ApiResponse.json(backend.mkdir(fs(b), ApiRequest.need(b, "path")), 201);
            });
            case "create": return post(r, () -> {
                JSONObject b = r.json();
                return ApiResponse.json(backend.createFile(fs(b), ApiRequest.need(b, "path")), 201);
            });
            case "rename": return post(r, () -> {
                JSONObject b = r.json();
                String name = ApiRequest.need(b, "name");
                if (name.contains("/") || name.equals(".") || name.equals("..")) {
                    throw new ApiException(400, "a new name is a name, not a path", "to move it, use POST /api/files/move");
                }
                return ApiResponse.json(backend.rename(fs(b), ApiRequest.need(b, "path"), name));
            });
            case "delete": return post(r, () -> {
                JSONObject b = r.json();
                Boolean permanent = b.has("permanent") && !b.isNull("permanent") ? b.optBoolean("permanent") : null;
                return ApiResponse.json(backend.delete(fs(b), ApiRequest.paths(b), permanent));
            });
            case "copy": case "move": case "zip": return post(r, () -> transfer(r, tail.get(0), true));
            case "extract": return post(r, () -> transfer(r, "extract", false));
            case "properties":
                if (r.method.equals("GET")) {
                    return ApiResponse.json(backend.properties(r.q("fs", "local"),
                            Collections.singletonList(needQ(r, "path"))));
                }
                if (r.method.equals("POST")) {
                    JSONObject b = r.json();
                    return ApiResponse.json(backend.properties(fs(b), ApiRequest.paths(b)));
                }
                return notAllowed(r, "GET", "POST");
            default: return noRoute(r);
        }
    }

    private ApiResponse transfer(ApiRequest r, String kind, boolean many) throws Exception {
        JSONObject b = r.json();
        List<String> paths = many ? ApiRequest.paths(b) : Collections.singletonList(ApiRequest.need(b, "path"));
        String fs = fs(b);
        String destFs = b.optString("dest_fs", fs);
        String dest = ApiRequest.need(b, "dest");
        long waitMs = b.optBoolean("wait", false) ? MAX_WAIT_MS : 0;
        JSONObject job = backend.transfer(kind, fs, paths, destFs, dest, waitMs);
        boolean finished = !"running".equals(job.optString("state"));
        return ApiResponse.json(job, finished ? 200 : 202);
    }

    private ApiResponse jobs(ApiRequest r, List<String> tail) throws Exception {
        if (tail.isEmpty()) return get(r, () -> ApiResponse.json(backend.jobs()));
        if (tail.size() != 1) return noRoute(r);
        String id = tail.get(0);
        switch (r.method) {
            case "GET": return ApiResponse.json(found(backend.job(id), "job " + id));
            case "DELETE": return ApiResponse.json(found(backend.cancelJob(id), "job " + id));
            default: return notAllowed(r, "GET", "DELETE");
        }
    }

    private ApiResponse bookmarks(ApiRequest r) throws Exception {
        switch (r.method) {
            case "GET": return ApiResponse.json(backend.bookmarks());
            case "POST": {
                JSONObject b = r.json();
                return ApiResponse.json(backend.addBookmark(fs(b), ApiRequest.need(b, "path"),
                        b.optString("label", "")), 201);
            }
            case "DELETE": {
                JSONObject b = r.json();
                String fs = r.q("fs") != null ? r.q("fs") : fs(b);
                String path = r.q("path") != null ? r.q("path") : ApiRequest.need(b, "path");
                if (!backend.removeBookmark(fs, path)) throw new ApiException(404, "no bookmark for " + path);
                return ok();
            }
            default: return notAllowed(r, "GET", "POST", "DELETE");
        }
    }

    private ApiResponse trash(ApiRequest r, List<String> tail) throws Exception {
        if (tail.isEmpty()) {
            switch (r.method) {
                case "GET": return ApiResponse.json(backend.trash());
                case "DELETE": return ApiResponse.json(backend.emptyTrash());
                default: return notAllowed(r, "GET", "DELETE");
            }
        }
        String id = tail.get(0);
        if (tail.size() == 2 && tail.get(1).equals("restore")) {
            return post(r, () -> ApiResponse.json(backend.restoreTrash(id)));
        }
        if (tail.size() == 1) {
            if (r.method.equals("DELETE")) return ApiResponse.json(backend.purgeTrash(id));
            return notAllowed(r, "DELETE");
        }
        return noRoute(r);
    }

    private ApiResponse tabs(ApiRequest r, List<String> tail) throws Exception {
        if (tail.isEmpty()) {
            switch (r.method) {
                case "GET": return ApiResponse.json(backend.tabs());
                case "POST": {
                    JSONObject b = r.json();
                    return ApiResponse.json(backend.openTab(fs(b), b.optString("path", null)), 201);
                }
                default: return notAllowed(r, "GET", "POST");
            }
        }
        if (tail.size() != 1) return noRoute(r);
        int id;
        try { id = Integer.parseInt(tail.get(0)); }
        catch (NumberFormatException e) { throw new ApiException(400, "a tab id is a number, not \"" + tail.get(0) + "\""); }
        switch (r.method) {
            case "PATCH": {
                JSONObject b = r.json();
                String path = b.optString("path", "");
                Boolean current = b.has("current") ? b.optBoolean("current") : null;
                if (path.isEmpty() && current == null) {
                    throw new ApiException(400, "nothing to change", "send \"path\" to navigate, \"current\": true to switch to it");
                }
                return ApiResponse.json(backend.updateTab(id, path.isEmpty() ? null : path, current));
            }
            case "DELETE":
                if (!backend.closeTab(id)) throw new ApiException(404, "no tab " + id, "GET /api/tabs lists them");
                return ok();
            default: return notAllowed(r, "PATCH", "DELETE");
        }
    }

    private ApiResponse settings(ApiRequest r) throws Exception {
        switch (r.method) {
            case "GET": return ApiResponse.json(backend.settings());
            case "PATCH": {
                JSONObject b = r.json();
                if (b.length() == 0) throw new ApiException(400, "nothing to change", "GET /api/settings shows the fields");
                if (b.has("sort") && !SORTS.contains(b.optString("sort"))) throw bad("sort", b.optString("sort"), SORTS);
                return ApiResponse.json(backend.updateSettings(b));
            }
            default: return notAllowed(r, "GET", "PATCH");
        }
    }

    private ApiResponse defaultApps(ApiRequest r, List<String> tail) throws Exception {
        if (tail.isEmpty()) return get(r, () -> ApiResponse.json(backend.defaultApps()));
        if (tail.size() == 1 && r.method.equals("DELETE")) {
            if (!backend.clearDefaultApp(tail.get(0))) throw new ApiException(404, "no default app for " + tail.get(0));
            return ok();
        }
        return tail.size() == 1 ? notAllowed(r, "DELETE") : noRoute(r);
    }

    // ---- helpers ----------------------------------------------------------------

    private interface Handler { ApiResponse run() throws Exception; }

    private ApiResponse get(ApiRequest r, Handler h) throws Exception {
        return r.method.equals("GET") ? h.run() : notAllowed(r, "GET");
    }

    private ApiResponse post(ApiRequest r, Handler h) throws Exception {
        return r.method.equals("POST") ? h.run() : notAllowed(r, "POST");
    }

    private static String fs(JSONObject b) {
        String fs = b.optString("fs", "local");
        return fs.isEmpty() ? "local" : fs;
    }

    private static String needQ(ApiRequest r, String name) {
        String v = r.q(name);
        if (v == null || v.isEmpty()) throw new ApiException(400, "?" + name + "= is required");
        return v;
    }

    private static JSONObject found(JSONObject o, String what) {
        if (o == null) throw new ApiException(404, "no " + what);
        return o;
    }

    private static ApiException bad(String field, String value, Set<String> allowed) {
        return new ApiException(400, field + " \"" + value + "\" is not one of " + String.join(", ", new java.util.TreeSet<>(allowed)));
    }

    private static ApiResponse ok() throws Exception {
        return ApiResponse.json(new JSONObject().put("ok", true));
    }

    private static ApiResponse noRoute(ApiRequest r) {
        return ApiResponse.error(404, "no route for " + r.method + " " + r.path, "GET /api/docs lists every endpoint");
    }

    private static ApiResponse notAllowed(ApiRequest r, String... allowed) {
        String allow = String.join(", ", allowed);
        ApiResponse e = ApiResponse.error(405, r.method + " not allowed on " + r.path + " (allowed: " + allow + ")", null);
        Map<String, String> h = Collections.singletonMap("Allow", allow);
        return new ApiResponse(405, e.contentType, e.body, null, -1, h);
    }

    private static Set<String> set(String... xs) { return new HashSet<>(Arrays.asList(xs)); }
}
