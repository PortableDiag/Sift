package com.sift.explorer.api;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Just enough HTTP/1.1 to serve a local API, and no more — the same reasoning as Lull's: a web
 * server dependency would be larger than the feature, and ship to everyone who never turns it on.
 * Parse a request line, read the headers, leave the body on the socket for the handler.
 *
 * <p>Free of Android types, so parsing, routing, auth and the error shapes are JVM unit-tested.
 */
public final class Http {

    private Http() {}

    /** JSON bodies above this are refused: nothing this API accepts is a megabyte of JSON. */
    public static final int MAX_BODY = 1 << 20;

    /** A request line longer than this is not a request, it is someone probing. */
    private static final int MAX_LINE = 8 * 1024;

    private static final int MAX_HEADERS = 100;

    /**
     * Reads one request's head off {@code input}. Returns null at a clean end of stream (a port
     * scanner that connected and left), throws {@link ApiException} for something that arrived
     * but is not a request this server can answer.
     */
    public static ApiRequest parse(InputStream input) throws IOException {
        String requestLine = readLine(input);
        if (requestLine == null || requestLine.trim().isEmpty()) return null;

        String[] parts = requestLine.split(" ");
        if (parts.length < 2) throw new ApiException(400, "malformed request line");
        String method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];

        Map<String, String> headers = new HashMap<>();
        for (int i = 0; ; i++) {
            if (i > MAX_HEADERS) throw new ApiException(413, "too many headers");
            String line = readLine(input);
            if (line == null || line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }

        long length = -1;
        String cl = headers.get("content-length");
        if (cl != null) {
            try { length = Long.parseLong(cl.trim()); }
            catch (NumberFormatException e) { throw new ApiException(400, "bad Content-Length"); }
            if (length < 0) throw new ApiException(400, "bad Content-Length");
        }
        if (headers.containsKey("transfer-encoding")) {
            throw new ApiException(411, "chunked bodies are not supported; send a Content-Length",
                    "curl --data-binary @file or -T file sets one");
        }

        int q = target.indexOf('?');
        String rawPath = q < 0 ? target : target.substring(0, q);
        String rawQuery = q < 0 ? "" : target.substring(q + 1);
        String path = decode(rawPath, false);
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.isEmpty()) path = "/";
        return new ApiRequest(method, path, parseQuery(rawQuery), headers,
                length == 0 ? null : input, length);
    }

    public static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(decode(name, true), decode(value, true));
        }
        return out;
    }

    /** A {@code +} is a space in a query string but a literal {@code +} in a path. */
    public static String decode(String raw, boolean query) {
        try { return URLDecoder.decode(query ? raw : raw.replace("+", "%2B"), "UTF-8"); }
        catch (Exception e) { return raw; }
    }

    /** {@code ""} (a bare {@code ?flag}), 1, true, yes, on are true; 0, false, no, off are false. */
    public static boolean truthy(String v, String name) {
        String s = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "": case "1": case "true": case "yes": case "on": return true;
            case "0": case "false": case "no": case "off": return false;
            default: throw new ApiException(400, name + " must be true or false, not \"" + v + "\"");
        }
    }

    /** One CRLF- (or bare LF-) terminated line, as ISO-8859-1. */
    private static String readLine(InputStream input) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = input.read();
            if (b == -1) return sb.length() == 0 ? null : sb.toString();
            if (b == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') sb.setLength(n - 1);
                return sb.toString();
            }
            sb.append((char) b);
            if (sb.length() > MAX_LINE) throw new ApiException(413, "header line too long");
        }
    }

    /** Reads at most {@code limit} bytes of the underlying stream, and never closes it. */
    static final class Bounded extends FilterInputStream {
        private long left;

        Bounded(InputStream in, long limit) { super(in); this.left = limit; }

        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int b = super.read();
            if (b >= 0) left--;
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = super.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }

        @Override public void close() {}
    }
}
