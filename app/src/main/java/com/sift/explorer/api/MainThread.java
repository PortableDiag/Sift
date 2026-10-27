package com.sift.explorer.api;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs a block on the main thread and waits for its answer. Requests are served on socket
 * threads; tabs, activities and the theme insist on the main one.
 *
 * <p>The wait is bounded: a request that hangs because the main thread is wedged is worse than
 * one that says so — an agent can retry a 503, not a socket that never answers.
 */
public final class MainThread {

    public static final long TIMEOUT_MS = 5_000;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private MainThread() {}

    public interface Block<T> { T run() throws Exception; }

    public interface Action { void run() throws Exception; }

    public static <T> T get(Block<T> block) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return block.run();
        final CountDownLatch latch = new CountDownLatch(1);
        final Object[] result = new Object[1];
        final Throwable[] failure = new Throwable[1];
        HANDLER.post(() -> {
            try { result[0] = block.run(); }
            catch (Throwable t) { failure[0] = t; }
            finally { latch.countDown(); }
        });
        if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new ApiException(503, "the app did not answer within " + TIMEOUT_MS + "ms");
        }
        if (failure[0] instanceof Exception) throw (Exception) failure[0];
        if (failure[0] != null) throw new ApiException(500, failure[0].toString());
        @SuppressWarnings("unchecked") T t = (T) result[0];
        return t;
    }

    public static void run(Action action) throws Exception {
        get(() -> { action.run(); return null; });
    }

    public static void post(Runnable r) { HANDLER.post(r); }
}
