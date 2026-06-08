package com.gme.pay.gateway.filter;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 unit tests for {@link HmacSignatureVerifier}.
 *
 * <p>No Spring context, no Testcontainers, no running server — all tests are deterministic
 * and self-contained. Pre-computed HMAC vectors were generated independently with Python's
 * {@code hmac.new(secret, canonical, sha256).hexdigest()}.
 */
class HmacSignatureVerifierTest {

    private static final String SECRET    = "sk_test_xyz";
    private static final String METHOD    = "POST";
    private static final String PATH      = "/v1/rates";
    private static final String TIMESTAMP = "2026-06-08T10:00:00.000Z";
    private static final byte[] BODY      = "{\"send_currency\":\"USD\"}".getBytes(StandardCharsets.UTF_8);

    // -----------------------------------------------------------------------
    // SHA-256 of empty string (well-known constant, RFC 6234)
    // -----------------------------------------------------------------------
    private static final String SHA256_EMPTY =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    // -----------------------------------------------------------------------
    // Tests for sha256Hex
    // -----------------------------------------------------------------------

    @Test
    void sha256OfEmptyBytes_equalsKnownConstant() {
        String result = HmacSignatureVerifier.sha256Hex(new byte[0]);
        assertEquals(SHA256_EMPTY, result,
                "SHA-256 of empty byte array must match the well-known RFC constant");
    }

    @Test
    void sha256OfKnownInput_equalsExpected() {
        // echo -n "hello" | sha256sum = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        byte[] hello = "hello".getBytes(StandardCharsets.UTF_8);
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                HmacSignatureVerifier.sha256Hex(hello));
    }

    // -----------------------------------------------------------------------
    // Tests for buildCanonicalString
    // -----------------------------------------------------------------------

    @Test
    void canonicalString_hasCorrectNewlineDelimitedFormat() {
        String canonical = HmacSignatureVerifier.buildCanonicalString(
                METHOD, PATH, TIMESTAMP, new byte[0]);
        // Format: METHOD\nPATH_WITH_QUERY\nTIMESTAMP\nBODY_SHA256
        String[] parts = canonical.split("\n", -1);
        assertEquals(4, parts.length, "Canonical string must have exactly 4 newline-separated parts");
        assertEquals(METHOD,    parts[0]);
        assertEquals(PATH,      parts[1]);
        assertEquals(TIMESTAMP, parts[2]);
        assertEquals(SHA256_EMPTY, parts[3], "Body-less requests use SHA-256 of empty string");
    }

    @Test
    void canonicalString_withBody_hasBodyHashInFourthPart() {
        String canonical = HmacSignatureVerifier.buildCanonicalString(
                METHOD, PATH, TIMESTAMP, BODY);
        String[] parts = canonical.split("\n", -1);
        String expectedBodyHash = HmacSignatureVerifier.sha256Hex(BODY);
        assertEquals(expectedBodyHash, parts[3]);
    }

    // -----------------------------------------------------------------------
    // Tests for computeHmac + verifySignature
    // -----------------------------------------------------------------------

    @Test
    void validSignature_passesVerification() {
        String canonical  = HmacSignatureVerifier.buildCanonicalString(
                METHOD, PATH, TIMESTAMP, BODY);
        String computedSig = HmacSignatureVerifier.computeHmac(SECRET, canonical);

        assertTrue(HmacSignatureVerifier.verifySignature(computedSig, computedSig),
                "Signature must verify against itself");
    }

    @Test
    void tamperedBody_failsVerification() {
        byte[] originalBody = BODY.clone();
        byte[] tamperedBody  = BODY.clone();
        tamperedBody[0] = (byte) (tamperedBody[0] ^ 0xFF); // flip 1 byte

        String canonicalOriginal = HmacSignatureVerifier.buildCanonicalString(
                METHOD, PATH, TIMESTAMP, originalBody);
        String canonicalTampered  = HmacSignatureVerifier.buildCanonicalString(
                METHOD, PATH, TIMESTAMP, tamperedBody);

        String sigForOriginal = HmacSignatureVerifier.computeHmac(SECRET, canonicalOriginal);
        String sigForTampered  = HmacSignatureVerifier.computeHmac(SECRET, canonicalTampered);

        assertFalse(HmacSignatureVerifier.verifySignature(sigForOriginal, sigForTampered),
                "Signature of original body must NOT match signature of tampered body");
    }

    @Test
    void wrongSecret_failsVerification() {
        String canonical    = HmacSignatureVerifier.buildCanonicalString(
                METHOD, PATH, TIMESTAMP, BODY);
        String correctSig   = HmacSignatureVerifier.computeHmac(SECRET, canonical);
        String wrongSig     = HmacSignatureVerifier.computeHmac("wrong_secret", canonical);

        assertFalse(HmacSignatureVerifier.verifySignature(correctSig, wrongSig),
                "Signatures produced with different secrets must not match");
    }

    @Test
    void getRequest_usesEmptyStringSha256ForBodyHash() {
        // GET requests have no body — the body hash must be SHA-256("") = SHA256_EMPTY
        String canonical = HmacSignatureVerifier.buildCanonicalString(
                "GET", "/v1/balance", TIMESTAMP, new byte[0]);
        assertTrue(canonical.endsWith("\n" + SHA256_EMPTY),
                "GET canonical string must end with SHA-256 of empty string");
    }

    @Test
    void verifySignature_nullInputs_returnFalse() {
        assertFalse(HmacSignatureVerifier.verifySignature(null, "abc"));
        assertFalse(HmacSignatureVerifier.verifySignature("abc", null));
        assertFalse(HmacSignatureVerifier.verifySignature(null, null));
    }

    @Test
    void computeHmac_isDeterministic() {
        String canonical = HmacSignatureVerifier.buildCanonicalString(
                METHOD, PATH, TIMESTAMP, BODY);
        String sig1 = HmacSignatureVerifier.computeHmac(SECRET, canonical);
        String sig2 = HmacSignatureVerifier.computeHmac(SECRET, canonical);
        assertEquals(sig1, sig2, "HMAC must be deterministic for identical inputs");
    }
}
