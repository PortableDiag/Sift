package com.sift.explorer.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A parsed request. {@link #path} is percent-decoded; header names are lower-cased.
 *
 * <p>The body is not read up front. A JSON body is read (and capped) when a handler asks for it;
 * an upload is streamed straight to the file, so a gigabyte video never sits in the heap.
 */
public class ApiRequest {

    public final String method;
    public final String path;
    public final Map<String, String> query;
    public final Map<String, String> headers;
    private final InputStream body;
    /** -1 when the request carried no Content-Length. */
    public final long contentLength;
    private byte[] cached;

    public ApiRequest(String method, String path, Map<String, String> query,
                      Map<String, String> headers, InputStream body, long contentLength) {
        this.method = method;
        this.path = path;
        this.query = query == null ? Collections.emptyMap() : query;
        this.headers = headers == null ? Collections.emptyMap() : headers;
        this.body = body == null ? new ByteArrayInputStream(new byte[0]) : body;
        this.contentLength = contentLength;
    }

    /** For tests: a request with an in-memory body. */
    public static ApiRequest of(String method, String path, Map<String, String> query,
                                Map<String, String> headers, String body) {
        byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        return new ApiRequest(method, path, query, headers, new ByteArrayInputStream(b), b.length);
    }

    public String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }

    public String q(String name) { return query.get(name); }

    public String q(String name, String fallback) {
        String v = query.get(name);
        return v == null || v.isEmpty() ? fallback : v;
    }

    public int qInt(String name, int fallback) {
        String v = query.get(name);
        if (v == null || v.isEmpty()) return fallback;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { throw new ApiException(400, name + " must be a whole number, not \"" + v + "\""); }
    }

    /** {@code ?x}, {@code ?x=1}, {@code ?x=true} are true; {@code ?x=0}, {@code ?x=false} false. */
    public boolean qBool(String name, boolean fallback) {
        if (!query.containsKey(name)) return fallback;
        return Http.truthy(query.get(name), name);
    }

    /** The upload body, bounded by Content-Length. */
    public InputStream stream() {
        if (contentLength < 0) throw new ApiException(411, "an upload needs a Content-Length",
                "curl sets it for --data-binary @file and -T file");
        return new Http.Bounded(body, contentLength);
    }

    private byte[] bytes() {
        if (cached != null) return cached;
        // No Content-Length is no body: reading to EOF would wait out the socket timeout.
        if (contentLength <= 0) return cached = new byte[0];
        if (contentLength > Http.MAX_BODY) throw new ApiException(413, "JSON body larger than " + Http.MAX_BODY + " bytes");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = new Http.Bounded(body, contentLength);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > Http.MAX_BODY) throw new ApiException(413, "JSON body larger than " + Http.MAX_BODY + " bytes");
            }
            cached = out.toByteArray();
        } catch (IOException e) {
            throw new ApiException(400, "could not read the body: " + e.getMessage());
        }
        return cached;
    }

    /** The JSON body, or an empty object — a body-less POST is a legitimate "do it with defaults". */
    public JSONObject json() {
        String text = new String(bytes(), StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) return new JSONObject();
        try { return new JSONObject(text); }
        catch (Exception e) { throw new ApiException(400, "body is not a JSON object: " + e.getMessage()); }
    }

    /** A required string field of the JSON body. */
    public static String need(JSONObject o, String field) {
        String v = o.optString(field, "");
        if (o.isNull(field) || v.isEmpty()) throw new ApiException(400, "\"" + field + "\" is required");
        return v;
    }

    /** {@code "paths": [..]} or a single {@code "path"}; at least one is required. */
    public static List<String> paths(JSONObject o) {
        List<String> out = new ArrayList<>();
        JSONArray arr = o.optJSONArray("paths");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            String p = arr.optString(i, "");
            if (!p.isEmpty()) out.add(p);
        }
        if (out.isEmpty() && !o.optString("path", "").isEmpty()) out.add(o.optString("path"));
        if (out.isEmpty()) throw new ApiException(400, "\"paths\" (a list) or \"path\" is required");
        return out;
    }
}
