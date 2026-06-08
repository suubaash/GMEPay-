package com.gme.pay.auth.service;

import com.gme.pay.auth.domain.HmacSignatureVerifier;
import com.gme.pay.auth.domain.InMemoryNonceStore;
import com.gme.pay.auth.domain.PartnerCredentialPort;
import com.gme.pay.auth.dto.VerifyRequest;
import com.gme.pay.auth.dto.VerifyResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 unit tests for {@link AuthVerificationService}.
 * Uses an inline stub {@link PartnerCredentialPort} and {@link InMemoryNonceStore}.
 * No Spring context, no Docker — deterministic and fast.
 */
class AuthVerificationServiceTest {

    private static final String PARTNER_API_KEY = "pk_live_testkey12345678901234567890123";
    private static final long   PARTNER_ID      = 42L;
    private static final String HMAC_SECRET     = "test-secret-exactly-32-chars-here";

    private AuthVerificationService service;

    @BeforeEach
    void setUp() {
        // Stub credential port: returns the test credential for the known api key
        PartnerCredentialPort port = apiKey ->
                PARTNER_API_KEY.equals(apiKey)
                        ? Optional.of(new PartnerCredentialPort.ResolvedCredential(PARTNER_ID, HMAC_SECRET))
                        : Optional.empty();

        service = new AuthVerificationService(port, new InMemoryNonceStore());
    }

    // ── Helper: build a fully valid request ──────────────────────────────────

    private VerifyRequest validRequest() {
        String method = "POST";
        String path   = "/v1/payments?ref=abc";
        String ts     = Instant.now().toString();      // within 300-s window
        String nonce  = java.util.UUID.randomUUID().toString();
        byte[] body   = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String bodyHash = HmacSignatureVerifier.computeBodyHash(body);
        String canonical = HmacSignatureVerifier.buildCanonicalString(method, path, ts, bodyHash);
        String sig    = HmacSignatureVerifier.computeSignature(canonical, HMAC_SECRET);
        return new VerifyRequest(PARTNER_API_KEY, method, path, ts, nonce, sig, bodyHash);
    }

    // ── 1. Valid signed request returns valid=true + partnerId ───────────────

    @Test
    void verify_validRequest_returnsOk() {
        VerifyResponse resp = service.verify(validRequest());

        assertTrue(resp.valid());
        assertEquals(PARTNER_ID, resp.partnerId());
        assertNull(resp.errorCode());
        assertNotNull(resp.requestId());
    }

    // ── 2. Unknown API key returns INVALID_API_KEY ────────────────────────────

    @Test
    void verify_unknownApiKey_returnsInvalidApiKey() {
        VerifyRequest req = validRequest();
        VerifyRequest badKey = new VerifyRequest(
                "pk_live_unknownkey00000000000000000",
                req.httpMethod(), req.pathWithQuery(), req.timestamp(),
                req.nonce(), req.signature(), req.bodyHash());

        VerifyResponse resp = service.verify(badKey);

        assertFalse(resp.valid());
        assertEquals("INVALID_API_KEY", resp.errorCode());
    }

    // ── 3. Expired timestamp returns TIMESTAMP_DRIFT ─────────────────────────

    @Test
    void verify_expiredTimestamp_returnsTimestampDrift() {
        VerifyRequest orig = validRequest();
        // Timestamp 400 s in the past — well outside the 300-s window
        String staleTs = Instant.now().minusSeconds(400).toString();

        // Recompute signature with the stale timestamp so we reach the timestamp check
        String canonical = HmacSignatureVerifier.buildCanonicalString(
                orig.httpMethod(), orig.pathWithQuery(), staleTs, orig.bodyHash());
        String sig = HmacSignatureVerifier.computeSignature(canonical, HMAC_SECRET);

        VerifyRequest req = new VerifyRequest(
                orig.apiKey(), orig.httpMethod(), orig.pathWithQuery(),
                staleTs, orig.nonce(), sig, orig.bodyHash());

        VerifyResponse resp = service.verify(req);

        assertFalse(resp.valid());
        assertEquals("TIMESTAMP_DRIFT", resp.errorCode());
    }

    // ── 4. Tampered body (signature mismatch) returns INVALID_SIGNATURE ───────

    @Test
    void verify_tamperedBody_returnsInvalidSignature() {
        VerifyRequest orig = validRequest();
        // Keep the original signature but send a different bodyHash
        byte[] tamperedBody = "{\"amount\":99999}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String tamperedHash = HmacSignatureVerifier.computeBodyHash(tamperedBody);

        VerifyRequest req = new VerifyRequest(
                orig.apiKey(), orig.httpMethod(), orig.pathWithQuery(),
                orig.timestamp(), orig.nonce(), orig.signature(),
                tamperedHash);   // <── mismatched hash while signature unchanged

        VerifyResponse resp = service.verify(req);

        assertFalse(resp.valid());
        assertEquals("INVALID_SIGNATURE", resp.errorCode());
    }

    // ── 5. Replayed nonce returns REPLAY_DETECTED ─────────────────────────────

    @Test
    void verify_replayedNonce_returnsReplayDetected() {
        VerifyRequest first = validRequest();
        VerifyResponse resp1 = service.verify(first);
        assertTrue(resp1.valid(), "First request should succeed");

        // Reuse same nonce — same InMemoryNonceStore should detect replay.
        // Recompute signature with same nonce+timestamp so we reach the nonce check.
        // (Note: timestamp may fall out of window if test runs slowly — use fresh timestamp.)
        String ts = Instant.now().toString();
        String canonical = HmacSignatureVerifier.buildCanonicalString(
                first.httpMethod(), first.pathWithQuery(), ts, first.bodyHash());
        String sig = HmacSignatureVerifier.computeSignature(canonical, HMAC_SECRET);

        VerifyRequest replay = new VerifyRequest(
                first.apiKey(), first.httpMethod(), first.pathWithQuery(),
                ts, first.nonce(),   // <── same nonce
                sig, first.bodyHash());

        VerifyResponse resp2 = service.verify(replay);

        assertFalse(resp2.valid());
        assertEquals("REPLAY_DETECTED", resp2.errorCode());
    }
}
