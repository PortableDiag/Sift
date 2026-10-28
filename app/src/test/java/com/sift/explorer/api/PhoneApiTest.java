package com.sift.explorer.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Contract §2 (the proof header) and §3 (the pinned caller, PhoneAPI's own key and its two switches). */
public class PhoneApiTest {

    private static final String OWNER = "owner-key";
    private static final byte[] PROOF = new byte[32];
    static { for (int i = 0; i < 32; i++) PROOF[i] = (byte) i; }

    private PhoneApi.Grant grant = new PhoneApi.Grant("pa-key", PROOF, true, false);
    private final ApiRouter router = new ApiRouter(new ApiRouterTest.Fake().backend(), () -> OWNER, () -> grant);

    private ApiResponse call(String method, String path, String key, String challenge) {
        Map<String, String> h = new HashMap<>();
        if (key != null) h.put("x-api-key", key);
        if (challenge != null) h.put("x-phoneapi-challenge", challenge);
        return router.handle(ApiRequest.of(method, path, new HashMap<>(), h, null));
    }

    private static String expected(String challenge) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(PROOF, "HmacSHA256"));
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(challenge.getBytes("US-ASCII")));
    }

    @Test public void everyAnswerCarriesTheProof() throws Exception {
        String c = "abc123-XYZ";
        assertEquals(expected(c), call("GET", "/api/health", null, c).extraHeaders.get(PhoneApi.HEADER_PROOF));
        ApiResponse unauthorized = call("GET", "/api/locations", null, c);
        assertEquals(401, unauthorized.status);
        assertEquals(expected(c), unauthorized.extraHeaders.get(PhoneApi.HEADER_PROOF));
        ApiResponse notApi = call("GET", "/nope", null, c);
        assertEquals(404, notApi.status);
        assertEquals(expected(c), notApi.extraHeaders.get(PhoneApi.HEADER_PROOF));
        assertEquals(expected(c), call("GET", "/api/no-such-route", OWNER, c).extraHeaders.get(PhoneApi.HEADER_PROOF));
        assertFalse(expected(c).contains("="));
    }

    @Test public void noProofWithoutAChallengeOrAKey() {
        assertNull(call("GET", "/api/health", null, null).extraHeaders.get(PhoneApi.HEADER_PROOF));
        assertNull(call("GET", "/api/health", null, "bad\nchallenge").extraHeaders.get(PhoneApi.HEADER_PROOF));
        grant = null;
        assertNull(call("GET", "/api/health", null, "abc").extraHeaders.get(PhoneApi.HEADER_PROOF));
    }

    @Test public void phoneApisKeyFollowsItsSwitches() {
        assertEquals(200, call("GET", "/api/locations", "pa-key", null).status);
        assertEquals(403, call("POST", "/api/bookmarks", "pa-key", null).status);
        grant = new PhoneApi.Grant("pa-key", PROOF, false, true);
        assertEquals(403, call("GET", "/api/locations", "pa-key", null).status);
        assertEquals(200, call("GET", "/api/locations", OWNER, null).status);
        grant = null;
        assertEquals(401, call("GET", "/api/locations", "pa-key", null).status);
    }

    @Test public void onlyThePinnedPhoneApiIsTrusted() {
        String pin = PhoneApi.PIN.replace(":", "").toLowerCase();
        assertTrue(PhoneApi.callerTrusted("com.phoneapi.app", Collections.singletonList(pin)));
        assertTrue(PhoneApi.callerTrusted("com.phoneapi.app", Arrays.asList("00", PhoneApi.PIN)));
        assertFalse(PhoneApi.callerTrusted("com.phoneapi.app", Collections.singletonList("00" + pin.substring(2))));
        assertFalse(PhoneApi.callerTrusted("com.evil.app", Collections.singletonList(pin)));
        assertFalse(PhoneApi.callerTrusted("com.phoneapi.app", Collections.emptyList()));
    }
}
