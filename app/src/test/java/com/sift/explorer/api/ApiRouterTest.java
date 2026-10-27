package com.sift.explorer.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ApiRouterTest {

    private static final String KEY = "k3y";

    /** A backend that records the call it got and answers {"called": name}. */
    static final class Fake {
        final List<String> calls = new ArrayList<>();
        final List<Object[]> args = new ArrayList<>();
        byte[] uploaded;

        ApiBackend backend() {
            return (ApiBackend) Proxy.newProxyInstance(ApiBackend.class.getClassLoader(),
                    new Class<?>[]{ApiBackend.class}, (proxy, m, a) -> {
                        calls.add(m.getName());
                        args.add(a == null ? new Object[0] : a);
                        Class<?> r = m.getReturnType();
                        switch (m.getName()) {
                            case "read":
                                return new ApiBackend.Download(new ByteArrayInputStream("hello".getBytes()), 5, "a.txt", "text/plain");
                            case "write": {
                                ByteArrayOutputStream out = new ByteArrayOutputStream();
                                InputStream in = (InputStream) a[2];
                                int n; byte[] buf = new byte[4];
                                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                                uploaded = out.toByteArray();
                                return new JSONObject().put("size", uploaded.length);
                            }
                            case "transfer": return new JSONObject().put("state", (Long) a[5] > 0 ? "done" : "running");
                            case "job": case "cancelJob": return "7".equals(a[0]) ? new JSONObject().put("id", "7") : null;
                            case "closeTab": case "removeBookmark": case "clearDefaultApp": return Boolean.TRUE;
                        }
                        if (r == JSONObject.class) return new JSONObject().put("called", m.getName());
                        if (r == JSONArray.class) return new JSONArray().put(m.getName());
                        if (r == boolean.class) return true;
                        return null;
                    });
        }
    }

    private final Fake fake = new Fake();
    private final ApiRouter router = new ApiRouter(fake.backend(), () -> KEY);

    private ApiResponse call(String method, String target, String body) {
        return call(method, target, body, KEY);
    }

    private ApiResponse call(String method, String target, String body, String key) {
        int q = target.indexOf('?');
        String path = q < 0 ? target : target.substring(0, q);
        Map<String, String> query = Http.parseQuery(q < 0 ? "" : target.substring(q + 1));
        Map<String, String> headers = new HashMap<>();
        if (key != null) headers.put("x-api-key", key);
        return router.handle(ApiRequest.of(method, path, query, headers, body));
    }

    private static JSONObject json(ApiResponse r) throws Exception { return new JSONObject(r.text()); }

    @Test public void healthNeedsNoKey() throws Exception {
        ApiResponse r = call("GET", "/api/health", null, null);
        assertEquals(200, r.status);
        assertTrue(json(r).getBoolean("ok"));
    }

    @Test public void everythingElseNeedsTheKey() throws Exception {
        assertEquals(401, call("GET", "/api/files", null, null).status);
        assertEquals(401, call("GET", "/api/files", null, "wrong").status);
        assertEquals(401, call("GET", "/api/docs", null, "k3").status);
        assertTrue(fake.calls.isEmpty());
    }

    @Test public void bearerWorksToo() {
        Map<String, String> h = new HashMap<>();
        h.put("authorization", "Bearer " + KEY);
        assertEquals(200, router.handle(ApiRequest.of("GET", "/api/locations", null, h, null)).status);
    }

    @Test public void noKeySetLocksEverything() {
        ApiRouter locked = new ApiRouter(fake.backend(), () -> "");
        Map<String, String> h = new HashMap<>();
        h.put("x-api-key", "");
        assertEquals(401, locked.handle(ApiRequest.of("GET", "/api/files", null, h, null)).status);
    }

    @Test public void docsAndIndex() throws Exception {
        ApiResponse docs = call("GET", "/api/docs", null);
        assertEquals(200, docs.status);
        assertTrue(docs.contentType.startsWith("text/markdown"));
        assertTrue(docs.text().contains("# Sift local API"));
        JSONObject index = json(call("GET", "/api", null));
        assertEquals("Sift", index.getString("app"));
        assertEquals(ApiDocs.SUMMARY.length, index.getJSONArray("endpoints").length());
    }

    /** Every route the summary advertises must route somewhere, not 404. */
    @Test public void everySummaryLineRoutes() {
        for (String line : ApiDocs.SUMMARY) {
            String[] p = line.trim().split("\\s+");
            String target = p[1].replace("{id}", "7").replace("{type}", "video").replaceAll("\\?.*", "");
            ApiResponse r = call(p[0], target, "{\"path\":\"/x\",\"dest\":\"/y\",\"name\":\"n\",\"sort\":\"name\"}");
            assertTrue(line + " -> " + r.status + " " + r.text(), r.status != 404 && r.status != 405);
        }
    }

    @Test public void unknownRoutesAndMethods() throws Exception {
        assertEquals(404, call("GET", "/api/nope", null).status);
        assertEquals(404, call("GET", "/nope", null).status);
        ApiResponse r = call("DELETE", "/api/files", null);
        assertEquals(405, r.status);
        assertEquals("GET", r.extraHeaders.get("Allow"));
    }

    @Test public void listingPassesOptions() {
        assertEquals(200, call("GET", "/api/files?fs=abc&path=/x&hidden=1&sort=size&desc=true&q=mov&limit=5&offset=2", null).status);
        Object[] a = fake.args.get(0);
        assertEquals("abc", a[0]);
        assertEquals("/x", a[1]);
        ApiBackend.ListOptions o = (ApiBackend.ListOptions) a[2];
        assertTrue(o.hidden);
        assertEquals("size", o.sort);
        assertTrue(o.desc);
        assertEquals("mov", o.filter);
        assertEquals(5, o.limit);
        assertEquals(2, o.offset);
    }

    @Test public void listingDefaultsToLocalRoot() {
        call("GET", "/api/files", null);
        assertEquals("local", fake.args.get(0)[0]);
        assertNull(fake.args.get(0)[1]);
    }

    @Test public void badValuesAreSentences() throws Exception {
        ApiResponse r = call("GET", "/api/files?sort=colour", null);
        assertEquals(400, r.status);
        assertTrue(json(r).getString("error").contains("date, name, size, type"));
        assertEquals(400, call("GET", "/api/files?limit=ten", null).status);
        assertEquals(400, call("GET", "/api/files?hidden=maybe", null).status);
        assertEquals(400, call("GET", "/api/files/stat", null).status);
        assertEquals(400, call("POST", "/api/files/rename", "{\"path\":\"/a\",\"name\":\"x/y\"}").status);
        assertEquals(400, call("POST", "/api/files/delete", "{}").status);
        assertEquals(400, call("POST", "/api/files/mkdir", "not json").status);
        assertEquals(400, call("POST", "/api/open", "{\"path\":\"/a\",\"with\":\"telepathy\"}").status);
        assertEquals(400, call("PATCH", "/api/settings", "{}").status);
        assertEquals(400, call("PATCH", "/api/tabs/abc", "{\"current\":true}").status);
        assertTrue(fake.calls.toString(), fake.calls.isEmpty());
    }

    @Test public void deleteFollowsTheBinUnlessTold() {
        call("POST", "/api/files/delete", "{\"paths\":[\"/a\",\"/b\"]}");
        assertNull(fake.args.get(0)[2]);
        assertEquals(2, ((List<?>) fake.args.get(0)[1]).size());
        call("POST", "/api/files/delete", "{\"path\":\"/a\",\"permanent\":true}");
        assertEquals(Boolean.TRUE, fake.args.get(1)[2]);
    }

    @Test public void transfersAreJobs() {
        assertEquals(202, call("POST", "/api/files/copy", "{\"paths\":[\"/a\"],\"dest\":\"/b\"}").status);
        assertEquals("copy", fake.args.get(0)[0]);
        assertEquals("local", fake.args.get(0)[3]); // dest_fs defaults to fs
        assertEquals(200, call("POST", "/api/files/move", "{\"fs\":\"n\",\"paths\":[\"/a\"],\"dest\":\"/b\",\"dest_fs\":\"local\",\"wait\":true}").status);
        assertEquals("n", fake.args.get(1)[1]);
        assertEquals("local", fake.args.get(1)[3]);
        assertEquals(ApiRouter.MAX_WAIT_MS, fake.args.get(1)[5]);
        assertEquals(400, call("POST", "/api/files/zip", "{\"paths\":[\"/a\"]}").status);
    }

    @Test public void unknownJobIs404() {
        assertEquals(200, call("GET", "/api/jobs/7", null).status);
        assertEquals(404, call("GET", "/api/jobs/8", null).status);
        assertEquals(404, call("DELETE", "/api/jobs/8", null).status);
    }

    @Test public void downloadStreamsAndNamesTheFile() {
        ApiResponse r = call("GET", "/api/files/content?path=/a.txt", null);
        assertEquals(200, r.status);
        assertEquals(5, r.streamLength);
        assertTrue(r.extraHeaders.get("Content-Disposition").startsWith("attachment; filename=\"a.txt\""));
        assertTrue(call("GET", "/api/files/content?path=/a.txt&inline=1", null)
                .extraHeaders.get("Content-Disposition").startsWith("inline"));
    }

    @Test public void uploadStreamsExactlyTheBody() {
        ApiResponse r = call("PUT", "/api/files/content?path=/n.txt&overwrite=1", "0123456789");
        assertEquals(201, r.status);
        assertEquals("0123456789", new String(fake.uploaded, StandardCharsets.UTF_8));
        assertEquals(Boolean.TRUE, fake.args.get(0)[4]);
    }

    @Test public void uploadWithoutLengthIs411() {
        Map<String, String> h = new HashMap<>();
        h.put("x-api-key", KEY);
        Map<String, String> q = new HashMap<>();
        q.put("path", "/x");
        ApiRequest req = new ApiRequest("PUT", "/api/files/content", q, h, new ByteArrayInputStream(new byte[3]), -1);
        assertEquals(411, router.handle(req).status);
    }

    @Test public void backendRefusalsKeepTheirStatus() throws Exception {
        ApiBackend refusing = (ApiBackend) Proxy.newProxyInstance(ApiBackend.class.getClassLoader(),
                new Class<?>[]{ApiBackend.class}, (p, m, a) -> {
                    if (m.getName().equals("stat")) throw new ApiException(409, "nope", "try this");
                    throw new java.io.FileNotFoundException("/gone");
                });
        ApiRouter r = new ApiRouter(refusing, () -> KEY);
        Map<String, String> h = new HashMap<>();
        h.put("x-api-key", KEY);
        Map<String, String> q = new HashMap<>();
        q.put("path", "/a");
        ApiResponse s = r.handle(ApiRequest.of("GET", "/api/files/stat", q, h, null));
        assertEquals(409, s.status);
        assertEquals("try this", json(s).getString("hint"));
        assertEquals(404, r.handle(ApiRequest.of("GET", "/api/files", q, h, null)).status);
    }

    @Test public void tabsAndSettings() {
        assertEquals(201, call("POST", "/api/tabs", "{\"path\":\"/x\"}").status);
        assertEquals(200, call("PATCH", "/api/tabs/3", "{\"current\":true}").status);
        assertEquals(3, fake.args.get(1)[0]);
        assertEquals(200, call("DELETE", "/api/tabs/3", null).status);
        assertEquals(200, call("PATCH", "/api/settings", "{\"palette\":\"Zoro\"}").status);
        assertEquals(400, call("PATCH", "/api/settings", "{\"sort\":\"colour\"}").status);
    }

    @Test public void httpParsesAHead() throws Exception {
        String raw = "POST /api/files/rename?fs=a%20b&x=1+2 HTTP/1.1\r\nX-Api-Key: k\r\nContent-Length: 2\r\n\r\n{}";
        ApiRequest r = Http.parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
        assertEquals("POST", r.method);
        assertEquals("/api/files/rename", r.path);
        assertEquals("a b", r.q("fs"));
        assertEquals("1 2", r.q("x"));
        assertEquals("k", r.header("X-API-Key"));
        assertEquals(0, r.json().length());
    }

    @Test public void httpTrimsTrailingSlashAndKeepsPlusInPath() throws Exception {
        ApiRequest r = Http.parse(new ByteArrayInputStream("GET /api/a+b/ HTTP/1.1\n\n".getBytes()));
        assertEquals("/api/a+b", r.path);
        assertNull(Http.parse(new ByteArrayInputStream(new byte[0])));
    }

    @Test public void noContentLengthIsAnEmptyBodyNotAWait() {
        ApiRequest r = new ApiRequest("POST", "/api/x", null, null, new ByteArrayInputStream("{\"a\":1}".getBytes()), -1);
        assertEquals(0, r.json().length());
    }

    @Test public void chunkedIsRefused() {
        try {
            Http.parse(new ByteArrayInputStream("PUT /api/x HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes()));
            assertFalse("should have thrown", true);
        } catch (ApiException e) {
            assertEquals(411, e.status);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
