package com.sift.explorer.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.util.Log;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The key PhoneAPI holds, if any: its own API key (separate from the owner's, which it never
 * sees), the proof key, and what the owner let it do. They reach PhoneAPI only as an activity
 * result to its pinned release build, after the owner says yes on Sift's own screen — never a
 * file, a broadcast or the clipboard.
 */
public final class PhoneApiAccess {

    private static final String TAG = "SiftApi";

    private static final String FILE = "sift_settings";
    private static final String K_KEY = "phoneapi_key";
    private static final String K_PROOF = "phoneapi_proof";
    private static final String K_SEE = "phoneapi_see";
    private static final String K_CHANGE = "phoneapi_change";
    private static final String K_EVENT_PORT = "phoneapi_event_port";
    private static final String K_EVENT_KEY = "phoneapi_event_key";

    private PhoneApiAccess() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Null until a handshake, and after a revoke. */
    public static PhoneApi.Grant grant(Context c) {
        SharedPreferences p = sp(c);
        String key = p.getString(K_KEY, "");
        byte[] proof = PhoneApi.fromB64url(p.getString(K_PROOF, ""));
        if (key == null || key.isEmpty() || proof == null || proof.length != 32) return null;
        return new PhoneApi.Grant(key, proof, p.getBoolean(K_SEE, false), p.getBoolean(K_CHANGE, false));
    }

    /**
     * The owner said yes: a new key (a second handshake replaces the first), a fresh 32-byte
     * proof key, PhoneAPI's event port and key kept as the contract asks, and the local API
     * switched on.
     */
    public static PhoneApi.Grant create(Context c, boolean see, boolean change, int eventPort, String eventKey) {
        byte[] keyBytes = new byte[24];
        byte[] proof = new byte[32];
        SecureRandom rnd = new SecureRandom();
        rnd.nextBytes(keyBytes);
        rnd.nextBytes(proof);
        StringBuilder key = new StringBuilder();
        for (byte b : keyBytes) key.append(String.format("%02x", b));
        sp(c).edit()
                .putString(K_KEY, key.toString())
                .putString(K_PROOF, PhoneApi.b64url(proof))
                .putBoolean(K_SEE, see)
                .putBoolean(K_CHANGE, change)
                .putInt(K_EVENT_PORT, eventPort)
                .putString(K_EVENT_KEY, eventKey == null ? "" : eventKey)
                .commit();
        if (!ApiPrefs.enabled(c)) ApiPrefs.setEnabled(c, true);
        ApiService.startIfEnabled(c);
        Log.i(TAG, "phoneapi: key granted (see=" + see + ", change=" + change + ")");
        return grant(c);
    }

    /** PhoneAPI's key and proof key stop working at once; the owner's key is untouched. */
    public static void revoke(Context c) {
        sp(c).edit()
                .remove(K_KEY).remove(K_PROOF).remove(K_SEE).remove(K_CHANGE)
                .remove(K_EVENT_PORT).remove(K_EVENT_KEY)
                .apply();
        Log.i(TAG, "phoneapi: key revoked");
    }

    /**
     * Android runs no code on uninstall, so whoever next owns the package name would inherit the
     * key. Drop it when PhoneAPI is gone or no longer signed by the pin.
     */
    public static void revokeIfGone(Context c) {
        if (grant(c) != null && !PhoneApi.pinMatches(certs(c, PhoneApi.PKG))) revoke(c);
    }

    /** Every SHA-256 {@code pkg} is signed with (hex); empty when it is not installed. */
    @SuppressWarnings("deprecation")
    public static List<String> certs(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                SigningInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
                if (info == null) return Collections.emptyList();
                sigs = info.hasMultipleSigners() ? info.getApkContentsSigners() : info.getSigningCertificateHistory();
            } else {
                sigs = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures;
            }
            List<String> out = new ArrayList<>();
            if (sigs == null) return out;
            for (Signature s : sigs) {
                StringBuilder hex = new StringBuilder();
                for (byte b : MessageDigest.getInstance("SHA-256").digest(s.toByteArray())) {
                    hex.append(String.format("%02x", b));
                }
                out.add(hex.toString());
            }
            return out;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
