package com.sift.explorer.api;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The socket half of the API: accept a connection, read one request, write one response, close.
 * No keep-alive — the callers are scripts and agents, not a browser fetching forty assets, and a
 * connection that is always closed cannot be left half-read.
 *
 * <p>The accept loop hands each connection to a small pool, so one slow request (a large file
 * being drained over bad wifi, a copy waited on) cannot stop the next one being served.
 */
public class ApiServer {

    private static final String TAG = "SiftApi";
    private static final int WORKERS = 4;
    /** A client that connects and then says nothing gets dropped rather than held. */
    private static final int SOCKET_TIMEOUT_MS = 30_000;
    private static final int BACKLOG = 16;

    private final ApiRouter router;
    private final String bindAddress;
    private final int port;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private ExecutorService workers;

    public ApiServer(ApiRouter router, String bindAddress, int port) {
        this.router = router;
        this.bindAddress = bindAddress;
        this.port = port;
    }

    public boolean isRunning() { return running.get(); }

    public String address() { return bindAddress + ":" + port; }

    /** @throws IOException when the port is taken or the address cannot be bound. */
    public void start() throws IOException {
        if (running.get()) return;
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new java.net.InetSocketAddress(InetAddress.getByName(bindAddress), port), BACKLOG);
        serverSocket = socket;
        workers = Executors.newFixedThreadPool(WORKERS);
        running.set(true);
        Thread t = new Thread(() -> acceptLoop(socket), "sift-api-accept");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "listening on " + address());
    }

    public void stop() {
        if (!running.getAndSet(false)) return;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignore) {}
        if (workers != null) workers.shutdownNow();
        serverSocket = null;
        workers = null;
        Log.i(TAG, "stopped");
    }

    private void acceptLoop(ServerSocket socket) {
        while (running.get()) {
            final Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                // A close() while blocked in accept() lands here; that is a stop, not a fault.
                if (running.get()) Log.w(TAG, "accept failed", e);
                return;
            }
            ExecutorService pool = workers;
            if (pool == null || pool.isShutdown()) { closeQuiet(client); return; }
            try { pool.execute(() -> serve(client)); }
            catch (Exception e) { closeQuiet(client); }
        }
    }

    private void serve(Socket socket) {
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream input = new BufferedInputStream(socket.getInputStream());
            OutputStream output = new BufferedOutputStream(socket.getOutputStream());
            ApiResponse response;
            try {
                ApiRequest request = Http.parse(input);
                if (request == null) return;
                response = router.handle(request);
            } catch (ApiException e) {
                response = ApiResponse.error(e.status, e.getMessage(), e.hint);
            } catch (SocketTimeoutException e) {
                return;
            } catch (IOException e) {
                return;
            } catch (Exception e) {
                Log.w(TAG, "request failed", e);
                response = ApiResponse.error(500, e.getClass().getSimpleName() + ": " + e.getMessage(), null);
            }
            try { write(output, response); }
            catch (Exception e) { Log.w(TAG, "response failed", e); }
        } catch (IOException ignore) {
        } finally {
            closeQuiet(socket);
        }
    }

    private static void write(OutputStream output, ApiResponse r) throws IOException {
        long length = r.stream != null ? r.streamLength : r.body.length;
        StringBuilder head = new StringBuilder()
                .append("HTTP/1.1 ").append(r.status).append(' ').append(r.reason()).append("\r\n")
                .append("Content-Type: ").append(r.contentType).append("\r\n");
        if (length >= 0) head.append("Content-Length: ").append(length).append("\r\n");
        head.append("Connection: close\r\n")
                .append("Cache-Control: no-store\r\n")
                // A browser on this device may be how someone pokes at it; the key is still needed.
                .append("Access-Control-Allow-Origin: *\r\n")
                .append("Access-Control-Allow-Headers: X-API-Key, Authorization, Content-Type\r\n")
                .append("Access-Control-Allow-Methods: GET, POST, PUT, PATCH, DELETE, OPTIONS\r\n");
        for (Map.Entry<String, String> h : r.extraHeaders.entrySet()) {
            head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        head.append("\r\n");
        output.write(head.toString().getBytes(StandardCharsets.UTF_8));
        if (r.stream != null) {
            try (InputStream in = r.stream) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) output.write(buf, 0, n);
            }
        } else {
            output.write(r.body);
        }
        output.flush();
    }

    private static void closeQuiet(Socket s) {
        try { s.close(); } catch (IOException ignore) {}
    }
}
