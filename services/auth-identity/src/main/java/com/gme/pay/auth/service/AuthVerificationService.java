package com.gme.pay.auth.service;

import com.gme.pay.auth.audit.AuditPayload;
import com.gme.pay.auth.audit.AuthAuditEvents;
import com.gme.pay.auth.audit.AuthAuditTrail;
import com.gme.pay.auth.domain.HmacSignatureVerifier;
import com.gme.pay.auth.domain.NonceStore;
import com.gme.pay.auth.domain.PartnerCredentialPort;
import com.gme.pay.auth.domain.PartnerCredentialPort.ResolvedCredential;
import com.gme.pay.auth.domain.TimestampValidator;
import com.gme.pay.auth.dto.VerifyRequest;
import com.gme.pay.auth.dto.VerifyResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates HMAC-SHA256 partner request-signature verification.
 *
 * Steps (SEC-09 §3.3, API-05 §3.2):
 *  1. Resolve partner credential by API key → obtain hmac_secret and partner_id.
 *  2. Validate X-Timestamp within 300-second window.
 *  3. Check (partnerId, X-Nonce) for replay within 600-second window.
 *  4. Build canonical string and verify HMAC-SHA256 signature (constant-time compare).
 *
 * <h2>Audit (gap T5-1 / CISO §9)</h2>
 *
 * <p>Every one of the four rejection branches above now writes an {@code audit_log} row, via
 * {@link AuthAuditTrail#recordRejection} so the row commits independently of the rejected
 * request's own transaction. Before this, a brute-force against a partner api key, a replayed
 * nonce and a forged signature were <b>all invisible</b> — the method returned a
 * {@link VerifyResponse} to the gateway and left nothing behind.
 *
 * <p>What a rejection row records, and what it deliberately does not:
 *
 * <ul>
 *   <li>The error code, the request id, the HTTP method, and the request PATH — with the query
 *       string stripped. Query strings carry customer references and, in this platform, can
 *       carry personal data; an audit table is not the place to accumulate it, and the path
 *       alone answers "what were they trying to reach".</li>
 *   <li>The presented api key <i>prefix</i> only. The api key is a public identifier rather
 *       than secret material, but an unresolved one is attacker-supplied free text and there
 *       is no reason to store more of it than identifies the attempt.</li>
 *   <li>A truncated SHA-256 <b>fingerprint</b> of the presented signature — never the signature,
 *       and above all never the HMAC secret it was checked against. The fingerprint is what
 *       makes repeated identical replays visible as one credential rather than as noise.</li>
 * </ul>
 *
 * <p>The actor is {@code unverified:*} / {@code unattributed}: a request whose signature did not
 * verify has, by definition, no attested principal, and attributing the attempt to the partner
 * it was impersonating would be fabricating evidence against that partner.
 *
 * <h2>Why successful verifications are off by default</h2>
 *
 * <p>{@code gmepay.audit.auth.record-verify-success} (default {@code false}) gates the success
 * row. This endpoint is the api-gateway's per-request signature oracle, so it fires once per
 * partner API call — auditing every success would add a serialised {@code audit_log}
 * SELECT-tail + INSERT to the hot path of every partner request, and would funnel all of one
 * partner's traffic through a single per-aggregate chain (the chain is ordered, so that is a
 * write hotspot by construction).
 *
 * <p>That is a volume argument, not a security one, so it is worth being straight about what is
 * lost: with the flag off, this trail answers "which authentication attempts failed" but not
 * "which succeeded". The privileged successes — a token minted, a credential issued, authority
 * granted — are audited unconditionally elsewhere in this service, and the per-request record of
 * an authenticated partner call belongs to the gateway/transaction log rather than here. Turn
 * the flag on for a corridor or an environment under investigation.
 */
@Service
public class AuthVerificationService {

    /** Reject requests whose timestamp is further than this from server time. */
    private static final long TIMESTAMP_WINDOW_SECONDS = 300L;

    /** Retain nonces for replay detection for this duration. */
    private static final Duration NONCE_TTL = Duration.ofSeconds(600);

    private final PartnerCredentialPort credentialPort;
    private final NonceStore nonceStore;
    private final AuthAuditTrail audit;
    private final boolean recordSuccess;

    public AuthVerificationService(PartnerCredentialPort credentialPort,
                                   NonceStore nonceStore,
                                   AuthAuditTrail audit,
                                   @Value("${gmepay.audit.auth.record-verify-success:false}")
                                   boolean recordSuccess) {
        this.credentialPort = credentialPort;
        this.nonceStore = nonceStore;
        this.audit = audit;
        this.recordSuccess = recordSuccess;
    }

    /**
     * Verifies the partner request signature.
     *
     * @param request verification parameters (api key, headers, body hash)
     * @return {@link VerifyResponse} indicating success or failure with an error code
     */
    public VerifyResponse verify(VerifyRequest request) {
        String requestId = UUID.randomUUID().toString();

        // 1. Resolve partner credential
        Optional<ResolvedCredential> credOpt =
                credentialPort.findActiveByApiKey(request.apiKey());
        if (credOpt.isEmpty()) {
            // No partner resolved, so the chain is keyed by the presented key prefix: a
            // brute-force against one unknown key accumulates into one readable chain.
            auditFailure(AuthAuditEvents.unknownApiKeyAggregate(request.apiKey()),
                    "INVALID_API_KEY", request, requestId, null);
            return VerifyResponse.fail("INVALID_API_KEY", requestId);
        }
        ResolvedCredential cred = credOpt.get();
        String aggregateId = AuthAuditEvents.partnerAggregate(cred.partnerId());

        // 2. Timestamp window validation
        try {
            if (!TimestampValidator.isWithinWindow(request.timestamp(), Instant.now(),
                    TIMESTAMP_WINDOW_SECONDS)) {
                auditFailure(aggregateId, "TIMESTAMP_DRIFT", request, requestId, cred.partnerId());
                return VerifyResponse.fail("TIMESTAMP_DRIFT", requestId);
            }
        } catch (IllegalArgumentException e) {
            auditFailure(aggregateId, "TIMESTAMP_DRIFT", request, requestId, cred.partnerId());
            return VerifyResponse.fail("TIMESTAMP_DRIFT", requestId);
        }

        // 3. Nonce replay detection
        boolean nonceOk = nonceStore.checkAndSet(
                String.valueOf(cred.partnerId()), request.nonce(), NONCE_TTL);
        if (!nonceOk) {
            auditFailure(aggregateId, "REPLAY_DETECTED", request, requestId, cred.partnerId());
            return VerifyResponse.fail("REPLAY_DETECTED", requestId);
        }

        // 4. HMAC-SHA256 signature verification (constant-time)
        String canonical = HmacSignatureVerifier.buildCanonicalString(
                request.httpMethod(),
                request.pathWithQuery(),
                request.timestamp(),
                request.bodyHash());

        boolean signatureValid = HmacSignatureVerifier.verifySignature(
                request.signature(), canonical, cred.hmacSecret());

        if (!signatureValid) {
            auditFailure(aggregateId, "INVALID_SIGNATURE", request, requestId, cred.partnerId());
            return VerifyResponse.fail("INVALID_SIGNATURE", requestId);
        }

        if (recordSuccess) {
            audit.record(AuthAuditEvents.SESSION, aggregateId,
                    AuthAuditEvents.AUTH_VERIFY_SUCCEEDED, null,
                    describe(request, requestId, cred.partnerId()).json());
        }
        return VerifyResponse.ok(cred.partnerId(), requestId);
    }

    /**
     * Write the rejection row. Goes through {@code recordRejection} — its own transaction —
     * because a rejected request's transaction is the one thing that must not be able to take
     * the record of the rejection down with it.
     */
    private void auditFailure(String aggregateId, String errorCode, VerifyRequest request,
                              String requestId, Long partnerId) {
        audit.recordRejection(AuthAuditEvents.SESSION, aggregateId,
                AuthAuditEvents.AUTH_VERIFY_FAILED,
                describe(request, requestId, partnerId).put("errorCode", errorCode).json());
    }

    /**
     * The non-secret description of an attempt.
     *
     * <p>{@code signatureFingerprint} is a truncated SHA-256, never the signature; there is no
     * key or secret field at all. {@code path} has the query string stripped (see the class
     * javadoc). {@code nonce} is included because nonce reuse is the evidence for a replay, and
     * a nonce is by design a public, single-use value.
     */
    private static AuditPayload describe(VerifyRequest request, String requestId, Long partnerId) {
        AuditPayload payload = AuditPayload.of()
                .put("requestId", requestId)
                .put("partnerId", partnerId)
                .put("apiKeyPrefix", AuditPayload.leading(request.apiKey(), 20))
                .put("httpMethod", request.httpMethod())
                .put("path", stripQuery(request.pathWithQuery()))
                .put("timestamp", request.timestamp())
                .put("nonce", AuditPayload.leading(request.nonce(), 64))
                .put("signatureFingerprint", AuditPayload.fingerprint(request.signature()));
        return payload;
    }

    /**
     * Drop the query string. Audit rows are long-lived and widely readable; partner query
     * strings carry customer and reference data that has no business accumulating there, and
     * the path alone is what an investigator needs.
     */
    private static String stripQuery(String pathWithQuery) {
        if (pathWithQuery == null) {
            return null;
        }
        int q = pathWithQuery.indexOf('?');
        String path = q < 0 ? pathWithQuery : pathWithQuery.substring(0, q);
        return AuditPayload.leading(path, 256);
    }
}
