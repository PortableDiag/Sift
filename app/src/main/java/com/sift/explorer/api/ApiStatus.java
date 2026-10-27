package com.sift.explorer.api;

import java.util.ArrayList;
import java.util.List;

/**
 * What the API is doing, for the settings dialog and the drawer to show. The service publishes
 * here; listeners are told on whatever thread the change happened, so they hop to the UI.
 */
public final class ApiStatus {

    private static volatile boolean running;
    private static volatile String address = "";
    /** Why it is not running, when that is something the user can fix (a port in use). */
    private static volatile String error;
    private static final List<Runnable> LISTENERS = new ArrayList<>();

    private ApiStatus() {}

    public static boolean running() { return running; }

    public static String address() { return address; }

    public static String error() { return error; }

    public static synchronized void addListener(Runnable r) { LISTENERS.add(r); }

    public static synchronized void removeListener(Runnable r) { LISTENERS.remove(r); }

    static void update(boolean isRunning, String addr, String err) {
        running = isRunning;
        address = addr == null ? "" : addr;
        error = err;
        List<Runnable> copy;
        synchronized (ApiStatus.class) { copy = new ArrayList<>(LISTENERS); }
        for (Runnable r : copy) r.run();
    }
}
