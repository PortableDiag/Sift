package com.sift.explorer.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** A response, either fully in memory or streamed (a file download). */
public class ApiResponse {

    public final int status;
    public final String contentType;
    public final byte[] body;
    public final InputStream stream;
    public final long streamLength;
    public final Map<String, String> extraHeaders;

    public ApiResponse(int status, String contentType, byte[] body, InputStream stream,
                       long streamLength, Map<String, String> extraHeaders) {
        this.status = status;
        this.contentType = contentType;
        this.body = body == null ? new byte[0] : body;
        this.stream = stream;
        this.streamLength = streamLength;
        this.extraHeaders = extraHeaders == null ? Collections.emptyMap() : extraHeaders;
    }

    private static final Map<Integer, String> REASONS = new HashMap<>();
    static {
        REASONS.put(200, "OK"); REASONS.put(201, "Created"); REASONS.put(202, "Accepted");
        REASONS.put(204, "No Content"); REASONS.put(400, "Bad Request"); REASONS.put(401, "Unauthorized");
        REASONS.put(403, "Forbidden"); REASONS.put(404, "Not Found"); REASONS.put(405, "Method Not Allowed");
        REASONS.put(409, "Conflict"); REASONS.put(411, "Length Required"); REASONS.put(413, "Payload Too Large");
        REASONS.put(500, "Internal Server Error"); REASONS.put(502, "Bad Gateway");
        REASONS.put(503, "Service Unavailable");
    }

    public String reason() {
        String r = REASONS.get(status);
        return r == null ? "OK" : r;
    }

    /** The same response with one more header. */
    public ApiResponse withHeader(String name, String value) {
        Map<String, String> h = new HashMap<>(extraHeaders);
        h.put(name, value);
        return new ApiResponse(status, contentType, body, stream, streamLength, h);
    }

    /** The body as text, for tests. */
    public String text() { return new String(body, StandardCharsets.UTF_8); }

    public static ApiResponse json(JSONObject o) { return json(o, 200); }

    public static ApiResponse json(JSONObject o, int status) {
        return new ApiResponse(status, "application/json; charset=utf-8", toBytes(o), null, -1, null);
    }

    public static ApiResponse json(JSONArray a) {
        return new ApiResponse(200, "application/json; charset=utf-8", toBytes(a), null, -1, null);
    }

    public static ApiResponse text(String body, String type) {
        return new ApiResponse(200, type, body.getBytes(StandardCharsets.UTF_8), null, -1, null);
    }

    public static ApiResponse error(int status, String message, String hint) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", message);
            if (hint != null) o.put("hint", hint);
        } catch (Exception ignore) {}
        return json(o, status);
    }

    public static ApiResponse noContent() {
        return new ApiResponse(204, "text/plain", new byte[0], null, -1, null);
    }

    public static ApiResponse stream(InputStream in, String type, long length, String fileName, boolean inline) {
        Map<String, String> h = new HashMap<>();
        if (fileName != null && !fileName.isEmpty()) {
            h.put("Content-Disposition", (inline ? "inline" : "attachment")
                    + "; filename=\"" + fileName.replaceAll("[\"\\\\\r\n]", "_") + "\"");
        }
        return new ApiResponse(200, type, null, in, length, h);
    }

    private static byte[] toBytes(Object json) {
        try {
            String s = json instanceof JSONObject ? ((JSONObject) json).toString(2) : ((JSONArray) json).toString(2);
            return s.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return String.valueOf(json).getBytes(StandardCharsets.UTF_8);
        }
    }
}
