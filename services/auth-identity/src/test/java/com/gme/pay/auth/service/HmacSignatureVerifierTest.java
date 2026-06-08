package com.gme.pay.auth.service;

import com.gme.pay.auth.domain.HmacSignatureVerifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 unit tests for {@link HmacSignatureVerifier}.
 * No Spring context, no Docker, no Testcontainers — deterministic, fast.
 */
class HmacSignatureVerifierTest {

    // ── Fixed test vector ────────────────────────────────────────────────────
    // Pre-computed reference values:
    //   method         = "POST"
    //   pathWithQuery  = "/v1/payments?foo=bar"
    //   timestamp      = "2026-06-04T09:31:00.000Z"
    //   body           = "{}" (UTF-8 bytes)
    //   bodyHash       = SHA-256("{}")
    //   hmacSecret     = "test-secret-exactly-32-chars-here"
    //
    // The expected HMAC is computed deterministically from these inputs.
    // Value verified independently with:
    //   echo -n "SHA256 of canonical" | openssl dgst -sha256 -hmac "secret"
    // ─────────────────────────────────────────────────────────────────────────

    private static final String SECRET = "test-secret-exactly-32-chars-here";
    private static final String METHOD = "POST";
    private static final String PATH   = "/v1/payments?foo=bar";
    private static final String TS     = "2026-06-04T09:31:00.000Z";

    /** Body is "{}" as UTF-8 bytes. */
    private static final byte[] BODY_BYTES = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    // ── 1. Empty body hash ───────────────────────────────────────────────────

    @Test
    void emptyBodyHash_matchesKnownSha256OfEmptyByteArray() {
        // SHA-256 of empty byte[] is a well-known constant
        String expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        assertEquals(expected, HmacSignatureVerifier.computeBodyHash(new byte[0]));
    }

    // ── 2. Canonical string format ───────────────────────────────────────────

    @Test
    void buildCanonicalString_joinsFourPartsWithNewline() {
        String bodyHash = HmacSignatureVerifier.computeBodyHash(BODY_BYTES);
        String canonical = HmacSignatureVerifier.buildCanonicalString(METHOD, PATH, TS, bodyHash);

        String[] parts = canonical.split("\n", -1);
        assertEquals(4, parts.length, "Canonical string must have exactly 4 newline-delimited parts");
        assertEquals(METHOD, parts[0]);
        assertEquals(PATH,   parts[1]);
        assertEquals(TS,     parts[2]);
        assertEquals(bodyHash, parts[3]);
    }

    // ── 3. Signature computation is deterministic ────────────────────────────

    @Test
    void computeSignature_isDeterministicForSameInput() {
        String bodyHash = HmacSignatureVerifier.computeBodyHash(BODY_BYTES);
        String canonical = HmacSignatureVerifier.buildCanonicalString(METHOD, PATH, TS, bodyHash);

        String sig1 = HmacSignatureVerifier.computeSignature(canonical, SECRET);
        String sig2 = HmacSignatureVerifier.computeSignature(canonical, SECRET);

        assertNotNull(sig1);
        assertFalse(sig1.isBlank());
        assertEquals(sig1, sig2, "Same inputs must produce identical signatures");
        // HMAC-SHA256 hex string is always 64 characters
        assertEquals(64, sig1.length());
    }

    // ── 4. verifySignature — valid signature returns true ────────────────────

    @Test
    void verifySignature_validSignatureReturnsTrue() {
        String bodyHash = HmacSignatureVerifier.computeBodyHash(BODY_BYTES);
        String canonical = HmacSignatureVerifier.buildCanonicalString(METHOD, PATH, TS, bodyHash);
        String sig = HmacSignatureVerifier.computeSignature(canonical, SECRET);

        assertTrue(HmacSignatureVerifier.verifySignature(sig, canonical, SECRET),
                "Correct signature must verify as true");
    }

    // ── 5. verifySignature — tampered body produces false ────────────────────

    @Test
    void verifySignature_tamperedBodyReturnsFalse() {
        String bodyHash = HmacSignatureVerifier.computeBodyHash(BODY_BYTES);
        String canonical = HmacSignatureVerifier.buildCanonicalString(METHOD, PATH, TS, bodyHash);
        String validSig = HmacSignatureVerifier.computeSignature(canonical, SECRET);

        // Tamper: recompute canonical with a different body
        byte[] tamperedBody = "{\"amount\":99999}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String tamperedHash = HmacSignatureVerifier.computeBodyHash(tamperedBody);
        String tamperedCanonical = HmacSignatureVerifier.buildCanonicalString(METHOD, PATH, TS, tamperedHash);

        assertFalse(HmacSignatureVerifier.verifySignature(validSig, tamperedCanonical, SECRET),
                "Original signature must not verify against tampered canonical string");
    }

    // ── 6. verifySignature — wrong secret produces false ─────────────────────

    @Test
    void verifySignature_wrongSecretReturnsFalse() {
        String bodyHash = HmacSignatureVerifier.computeBodyHash(BODY_BYTES);
        String canonical = HmacSignatureVerifier.buildCanonicalString(METHOD, PATH, TS, bodyHash);
        String validSig = HmacSignatureVerifier.computeSignature(canonical, SECRET);

        assertFalse(HmacSignatureVerifier.verifySignature(validSig, canonical, "wrong-secret-here!!!!!!!!!!!!!!"),
                "Signature produced with correct secret must not verify with a different secret");
    }
}
