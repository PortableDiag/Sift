package com.sift.explorer.api;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/**
 * Whether the local API is on, what key opens it, and where it listens. Two rules the other
 * settings don't need: it is <b>off until someone turns it on</b>, and the key is <b>generated,
 * never typed</b> — it guards every operation the app can perform.
 */
public final class ApiPrefs {

    private static final String FILE = "sift_settings";
    private static final String K_ENABLED = "api_enabled";
    private static final String K_KEY = "api_key";
    private static final String K_PORT = "api_port";
    private static final String K_LAN = "api_lan";

    /** Sift's port: clear of the sibling apps on 8787–8799 (PermaFrost 8791, TrellisBridge 8792) and 8800–8801. */
    public static final int DEFAULT_PORT = 8802;
    public static final int MIN_PORT = 1024, MAX_PORT = 65535;

    /** 24 bytes of SecureRandom, hex — long enough that guessing it is not a strategy. */
    private static final int KEY_BYTES = 24;

    private ApiPrefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) { return sp(c).getBoolean(K_ENABLED, false); }

    public static void setEnabled(Context c, boolean on) {
        // Turning it on with no key would leave an open door: generate one in the same breath.
        if (on && key(c).isEmpty()) regenerateKey(c);
        sp(c).edit().putBoolean(K_ENABLED, on).apply();
    }

    public static String key(Context c) {
        String k = sp(c).getString(K_KEY, "");
        return k == null ? "" : k;
    }

    public static String regenerateKey(Context c) {
        byte[] bytes = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        String key = sb.toString();
        sp(c).edit().putString(K_KEY, key).apply();
        return key;
    }

    public static int port(Context c) {
        return Math.max(MIN_PORT, Math.min(MAX_PORT, sp(c).getInt(K_PORT, DEFAULT_PORT)));
    }

    public static void setPort(Context c, int port) {
        sp(c).edit().putInt(K_PORT, Math.max(MIN_PORT, Math.min(MAX_PORT, port))).apply();
    }

    /** False (the default) binds loopback only — this device, and a laptop through adb forward. */
    public static boolean lan(Context c) { return sp(c).getBoolean(K_LAN, false); }

    public static void setLan(Context c, boolean on) { sp(c).edit().putBoolean(K_LAN, on).apply(); }

    public static String bindAddress(Context c) { return lan(c) ? "0.0.0.0" : "127.0.0.1"; }
}
