package com.sift.explorer.api;

import com.sift.explorer.fs.FileOps;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Copies, moves, zips and extracts, run by the same {@link FileOps} engine as the progress dialog
 * in the app — so they report and cancel the same way. The last {@link #KEEP} finished jobs stay
 * readable; running ones are never dropped.
 */
final class Jobs {

    static final int KEEP = 50;

    private final AtomicInteger seq = new AtomicInteger();
    private final Map<String, Job> jobs = new LinkedHashMap<>();

    final class Job implements FileOps.Listener {
        final String id = String.valueOf(seq.incrementAndGet());
        final String kind;
        final JSONObject detail;
        final long started = System.currentTimeMillis();
        final CountDownLatch done = new CountDownLatch(1);
        volatile FileOps.Task task;
        volatile FileOps.Progress progress = new FileOps.Progress();
        volatile String state = "running", error;
        volatile long finished;
        /** Run on the main thread when it ends, e.g. to refresh the drawer. */
        Runnable after;

        Job(String kind, JSONObject detail) { this.kind = kind; this.detail = detail; }

        @Override public void onProgress(FileOps.Progress p) { progress = p; }

        @Override public void onDone(boolean ok, String err) {
            boolean cancelled = task != null && task.isCancelled();
            state = ok ? "done" : cancelled ? "cancelled" : "failed";
            error = ok ? null : err;
            finished = System.currentTimeMillis();
            done.countDown();
            if (after != null) after.run();
        }

        JSONObject json() {
            try {
                FileOps.Progress p = progress;
                JSONObject o = new JSONObject()
                        .put("id", id).put("kind", kind).put("state", state)
                        .put("started", started)
                        .put("finished", finished == 0 ? JSONObject.NULL : finished)
                        .put("error", error == null ? JSONObject.NULL : error)
                        .put("progress", new JSONObject()
                                .put("current", p.name)
                                .put("files_done", p.fileIndex).put("files", p.fileCount)
                                .put("bytes_done", p.doneBytes).put("bytes", p.totalBytes));
                for (Iterator<String> it = detail.keys(); it.hasNext(); ) {
                    String k = it.next();
                    o.put(k, detail.get(k));
                }
                return o;
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }

    synchronized Job create(String kind, JSONObject detail) {
        Job j = new Job(kind, detail);
        jobs.put(j.id, j);
        prune();
        return j;
    }

    /** Waits up to {@code waitMs} for the job, then describes it however far it got. */
    JSONObject await(Job j, long waitMs) throws InterruptedException {
        if (waitMs > 0) j.done.await(waitMs, TimeUnit.MILLISECONDS);
        return j.json();
    }

    synchronized JSONArray all() {
        JSONArray a = new JSONArray();
        List<Job> list = new ArrayList<>(jobs.values());
        for (int i = list.size() - 1; i >= 0; i--) a.put(list.get(i).json());
        return a;
    }

    synchronized JSONObject get(String id) {
        Job j = jobs.get(id);
        return j == null ? null : j.json();
    }

    synchronized JSONObject cancel(String id) {
        Job j = jobs.get(id);
        if (j == null) return null;
        if (j.task != null && "running".equals(j.state)) j.task.cancel();
        return j.json();
    }

    private void prune() {
        int finished = 0;
        for (Job j : jobs.values()) if (!"running".equals(j.state)) finished++;
        Iterator<Job> it = jobs.values().iterator();
        while (finished > KEEP && it.hasNext()) {
            if (!"running".equals(it.next().state)) { it.remove(); finished--; }
        }
    }
}
