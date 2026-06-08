package com.gme.pay.notify.signing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link WebhookSigningService}.
 *
 * <p>No Spring context, no network, fully deterministic.
 * Expected HMAC values were pre-computed offline:
 * <pre>
 *   Python cross-check:
 *   import hmac, hashlib
 *   hmac.new(b"super-secret-key", b"hello world", hashlib.sha256).hexdigest()
 *   => 62702cb79bceeba4d3f64ffd8253c3d44fbe19870d83e099f6498af265c83b61
 * </pre>
 */
class WebhookSigningServiceTest {

    // Pre-computed HMAC-SHA256("hello world", key="super-secret-key")
    private static final String KNOWN_BODY = "hello world";
    private static final String KNOWN_SECRET = "super-secret-key";
    private static final String KNOWN_HEX =
            "62702cb79bceeba4d3f64ffd8253c3d44fbe19870d83e099f6498af265c83b61";
    private static final String KNOWN_SIGNATURE = "sha256=" + KNOWN_HEX;

    private WebhookSigningService svc;

    @BeforeEach
    void setUp() {
        svc = new WebhookSigningService();
    }

    // ------------------------------------------------------------------
    // sign() — determinism tests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sign produces the expected HMAC-SHA256 for the known test vector")
    void sign_knownVector() {
        byte[] bodyBytes = KNOWN_BODY.getBytes(StandardCharsets.UTF_8);
        String result = svc.sign(bodyBytes, KNOWN_SECRET);
        assertEquals(KNOWN_SIGNATURE, result,
                "HMAC-SHA256 must match the pre-computed test vector");
    }

    @Test
    @DisplayName("sign is deterministic — same input always produces same output")
    void sign_isDeterministic() {
        byte[] body = "payload-123".getBytes(StandardCharsets.UTF_8);
        String first = svc.sign(body, "key-abc");
        String second = svc.sign(body, "key-abc");
        assertEquals(first, second, "sign() must be deterministic");
    }

    @Test
    @DisplayName("sign result always starts with sha256= prefix")
    void sign_prefixPresent() {
        byte[] body = "test".getBytes(StandardCharsets.UTF_8);
        String sig = svc.sign(body, "secret");
        assertTrue(sig.startsWith("sha256="), "signature must start with sha256=");
    }

    @Test
    @DisplayName("sign with different keys produces different signatures")
    void sign_differentKeys_differentSignatures() {
        byte[] body = "same body".getBytes(StandardCharsets.UTF_8);
        String sig1 = svc.sign(body, "key-one");
        String sig2 = svc.sign(body, "key-two");
        assertNotEquals(sig1, sig2, "Different keys must yield different signatures");
    }

    @Test
    @DisplayName("sign with empty body produces a non-empty signature")
    void sign_emptyBody() {
        String sig = svc.sign(new byte[0], "any-secret");
        assertTrue(sig.startsWith("sha256=") && sig.length() > 7);
    }

    // ------------------------------------------------------------------
    // verifySignature() — correctness and security tests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("verifySignature returns true for the known test vector")
    void verify_knownVector() {
        assertTrue(svc.verifySignature(KNOWN_BODY, KNOWN_SECRET, KNOWN_SIGNATURE));
    }

    @Test
    @DisplayName("verifySignature returns false when body is tampered (one byte changed)")
    void verify_tamperedBody_returnsFalse() {
        String tampered = KNOWN_BODY + "!";
        assertFalse(svc.verifySignature(tampered, KNOWN_SECRET, KNOWN_SIGNATURE),
                "Tampered body must not verify");
    }

    @Test
    @DisplayName("verifySignature returns false when header lacks sha256= prefix")
    void verify_noPrefixHeader_returnsFalse() {
        String headerWithoutPrefix = KNOWN_HEX; // no "sha256=" prefix
        assertFalse(svc.verifySignature(KNOWN_BODY, KNOWN_SECRET, headerWithoutPrefix),
                "Header without sha256= prefix must be rejected");
    }

    @Test
    @DisplayName("verifySignature returns false for null header")
    void verify_nullHeader_returnsFalse() {
        assertFalse(svc.verifySignature(KNOWN_BODY, KNOWN_SECRET, null));
    }

    @Test
    @DisplayName("verifySignature returns false when wrong secret is supplied")
    void verify_wrongSecret_returnsFalse() {
        assertFalse(svc.verifySignature(KNOWN_BODY, "wrong-secret", KNOWN_SIGNATURE));
    }
}
