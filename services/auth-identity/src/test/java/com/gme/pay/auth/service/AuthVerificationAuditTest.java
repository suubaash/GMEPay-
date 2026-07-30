package com.gme.pay.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.audit.AuditActors;
import com.gme.pay.auth.audit.AuthAuditEvents;
import com.gme.pay.auth.domain.HmacSignatureVerifier;
import com.gme.pay.auth.domain.InMemoryNonceStore;
import com.gme.pay.auth.domain.PartnerCredentialPort;
import com.gme.pay.auth.dto.VerifyRequest;
import com.gme.pay.auth.testsupport.RecordingAuditTrail;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T5-1 / CISO §9 — partner authentication outcomes must leave a trail. The audit found that this
 * path emitted "not even a log line", so a brute-force against a partner api key, a replayed nonce
 * and a forged signature were all invisible.
 *
 * <p>The assertions that matter most here are the negative ones:
 *
 * <ul>
 *   <li>a failed verification IS recorded (all four rejection branches), and recorded on the
 *       own-transaction path so it cannot be rolled back by the rejection;</li>
 *   <li>it is NOT attributed to the partner it was impersonating — attributing a failed attempt to
 *       the real principal would be fabricating evidence against them;</li>
 *   <li>the row contains no HMAC secret, no signature, and no query string.</li>
 * </ul>
 */
class AuthVerificationAuditTest {

    private static final String PARTNER_API_KEY = "pk_live_testkey12345678901234567890123";
    private static final long PARTNER_ID = 42L;
    private static final String HMAC_SECRET = "test-secret-exactly-32-chars-here";

    private RecordingAuditTrail audit;
    private PartnerCredentialPort port;

    @BeforeEach
    void setUp() {
        audit = new RecordingAuditTrail();
        port = apiKey -> PARTNER_API_KEY.equals(apiKey)
                ? Optional.of(new PartnerCredentialPort.ResolvedCredential(PARTNER_ID, HMAC_SECRET))
                : Optional.empty();
    }

    private AuthVerificationService service(boolean recordSuccess) {
        return new AuthVerificationService(port, new InMemoryNonceStore(), audit, recordSuccess);
    }

    // ── the four rejection branches ──────────────────────────────────────────────────────

    @Test
    @DisplayName("unknown api key: a row exists, keyed by the presented prefix, unattributed")
    void unknownApiKey_isAuditedAndNotAttributedToAnyone() {
        VerifyRequest req = new VerifyRequest("pk_live_attackerGuess000000000000000",
                "POST", "/v1/payments?customerRef=CUST-1234", Instant.now().toString(),
                UUID.randomUUID().toString(), "deadbeef", "hash");

        assertThat(service(false).verify(req).valid()).isFalse();

        var entry = audit.only(AuthAuditEvents.AUTH_VERIFY_FAILED);
        assertThat(entry.aggregateType()).isEqualTo(AuthAuditEvents.SESSION);
        // No partner resolved → keyed by the presented key prefix, so repeated guesses against the
        // same bad key accumulate into ONE readable chain instead of one chain per attempt.
        assertThat(entry.aggregateId()).startsWith("apikey:pk_live_attacker");
        assertThat(entry.aggregateId().length())
                .isLessThanOrEqualTo(AuthAuditEvents.MAX_AGGREGATE_ID_LEN);
        assertThat(entry.afterJson()).contains("\"errorCode\":\"INVALID_API_KEY\"");

        // The actor of a FAILED authentication is never an attested principal — this fake reports
        // what a request with no proven caller resolves to.
        assertThat(AuditActors.isAttributable(audit.currentActor())).isFalse();

        // Rejections go down the own-transaction path (see AuthAuditService): a row recording a
        // rejection must survive the rollback of the request that was rejected.
        assertThat(entry.rejection()).isTrue();
    }

    @Test
    @DisplayName("the failure row carries the path but NOT the query string")
    void failureRow_stripsTheQueryString() {
        VerifyRequest req = new VerifyRequest("pk_live_unknown0000000000000000000000",
                "POST", "/v1/payments?customerRef=CUST-1234&msisdn=821012345678",
                Instant.now().toString(), UUID.randomUUID().toString(), "sig", "hash");
        service(false).verify(req);

        var entry = audit.only(AuthAuditEvents.AUTH_VERIFY_FAILED);
        assertThat(entry.afterJson()).contains("\"path\":\"/v1/payments\"");
        // Query strings carry customer references and personal data; an append-only, exported
        // table is not where that should accumulate.
        assertThat(entry.mentions("customerRef")).isFalse();
        assertThat(entry.mentions("821012345678")).isFalse();
    }

    @Test
    @DisplayName("timestamp drift: audited on the resolved partner's chain")
    void timestampDrift_isAudited() {
        String staleTs = Instant.now().minusSeconds(400).toString();
        service(false).verify(signed("POST", "/v1/payments", staleTs,
                UUID.randomUUID().toString()));

        var entry = audit.only(AuthAuditEvents.AUTH_VERIFY_FAILED);
        assertThat(entry.aggregateId()).isEqualTo("partner:" + PARTNER_ID);
        assertThat(entry.afterJson()).contains("\"errorCode\":\"TIMESTAMP_DRIFT\"");
    }

    @Test
    @DisplayName("replayed nonce: audited, and the nonce is in the row (it is the evidence)")
    void replayedNonce_isAudited() {
        AuthVerificationService svc = service(false);
        String nonce = UUID.randomUUID().toString();
        assertThat(svc.verify(signed("POST", "/v1/payments", Instant.now().toString(), nonce))
                .valid()).isTrue();

        svc.verify(signed("POST", "/v1/payments", Instant.now().toString(), nonce));

        var entry = audit.only(AuthAuditEvents.AUTH_VERIFY_FAILED);
        assertThat(entry.afterJson()).contains("\"errorCode\":\"REPLAY_DETECTED\"");
        // A nonce is a public single-use value by design, and its reuse is the whole evidence for
        // a replay, so it belongs in the row.
        assertThat(entry.afterJson()).contains(nonce);
    }

    @Test
    @DisplayName("forged signature: audited, and NEITHER the signature nor the secret is stored")
    void invalidSignature_isAudited_withoutCredentialMaterial() {
        String forgedSignature = HmacSignatureVerifier.computeSignature(
                "POST\n/v1/payments\nx\ny", "attacker-guessed-secret");
        VerifyRequest req = new VerifyRequest(PARTNER_API_KEY, "POST", "/v1/payments",
                Instant.now().toString(), UUID.randomUUID().toString(), forgedSignature,
                HmacSignatureVerifier.computeBodyHash("{}".getBytes(StandardCharsets.UTF_8)));

        assertThat(service(false).verify(req).errorCode()).isEqualTo("INVALID_SIGNATURE");

        var entry = audit.only(AuthAuditEvents.AUTH_VERIFY_FAILED);
        assertThat(entry.aggregateId()).isEqualTo("partner:" + PARTNER_ID);
        assertThat(entry.afterJson()).contains("\"errorCode\":\"INVALID_SIGNATURE\"");
        // The partner's HMAC secret was in scope at the call site and must never appear...
        audit.assertNeverRecorded(HMAC_SECRET);
        // ...nor the presented signature itself — only a non-replayable fingerprint of it, which
        // is what makes repeated identical attempts correlatable.
        audit.assertNeverRecorded(forgedSignature);
        assertThat(entry.afterJson()).contains("\"signatureFingerprint\":\"sha256:");
    }

    // ── successes ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a successful verification is NOT audited by default (per-request oracle)")
    void success_isNotAuditedByDefault() {
        assertThat(service(false).verify(signed("POST", "/v1/payments",
                Instant.now().toString(), UUID.randomUUID().toString())).valid()).isTrue();

        // Documented trade-off, not an oversight: this endpoint fires once per partner API call,
        // and every success would add a serialised audit INSERT to that hot path. See
        // AuthVerificationService's javadoc.
        assertThat(audit.entries()).isEmpty();
    }

    @Test
    @DisplayName("with record-verify-success on, the success row lands on the partner's chain")
    void success_isAuditedWhenEnabled() {
        assertThat(service(true).verify(signed("POST", "/v1/payments",
                Instant.now().toString(), UUID.randomUUID().toString())).valid()).isTrue();

        var entry = audit.only(AuthAuditEvents.AUTH_VERIFY_SUCCEEDED);
        assertThat(entry.aggregateId()).isEqualTo("partner:" + PARTNER_ID);
        // A success is a normal (not rejection) write.
        assertThat(entry.rejection()).isFalse();
        audit.assertNeverRecorded(HMAC_SECRET);
    }

    // ── helper ───────────────────────────────────────────────────────────────────────────

    /** A correctly-signed request for the known partner credential. */
    private static VerifyRequest signed(String method, String path, String ts, String nonce) {
        String bodyHash = HmacSignatureVerifier.computeBodyHash(
                "{}".getBytes(StandardCharsets.UTF_8));
        String signature = HmacSignatureVerifier.computeSignature(
                HmacSignatureVerifier.buildCanonicalString(method, path, ts, bodyHash),
                HMAC_SECRET);
        return new VerifyRequest(PARTNER_API_KEY, method, path, ts, nonce, signature, bodyHash);
    }
}
