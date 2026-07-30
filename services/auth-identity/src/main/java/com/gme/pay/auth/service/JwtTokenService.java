package com.gme.pay.auth.service;

import com.gme.pay.auth.audit.AuditPayload;
import com.gme.pay.auth.audit.AuthAuditEvents;
import com.gme.pay.auth.audit.AuthAuditTrail;
import com.gme.pay.auth.domain.JwtHelper;
import com.gme.pay.auth.domain.JwtHelper.VerificationResult;
import com.gme.pay.auth.dto.IssueTokenRequest;
import com.gme.pay.auth.dto.IssueTokenResponse;
import com.gme.pay.auth.dto.VerifyTokenResponse;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/**
 * Issues and verifies short-lived HS256 service-to-service capability tokens
 * via {@link JwtHelper}. NOT a human operator session issuer — those tokens are
 * owned by Keycloak (ADR-011); this is the internal machine-token surface
 * consumed by api-gateway / ops BFF / config-registry.
 *
 * <h2>TTL policy</h2>
 * A request may request a shorter TTL than the configured default, or up to a
 * configured hard maximum ({@code gme.auth.jwt.max-token-ttl-seconds}). Requests
 * exceeding the maximum are clamped down to the maximum (never extended) so a
 * caller can never mint an over-long token.
 *
 * <h2>Audit (gap T5-1 / CISO §9)</h2>
 *
 * <p>Token issuance is the most consequential thing this service does: a token minted here makes
 * its bearer, to every service that trusts a GME signature, whoever the {@code sub} claim says.
 * It previously emitted <b>nothing</b> — not an audit row, not a log line — so "which identities
 * were minted, by whom, with what claims" had no answer at all, and a stolen internal-auth
 * secret could be used to mint operator tokens indefinitely and invisibly.
 *
 * <p>Now:
 *
 * <ul>
 *   <li>{@link AuthAuditEvents#TOKEN_ISSUED} on every successful mint — unconditionally. This is
 *       a low-volume, high-privilege path (only the gateway resolver and the ops BFF mint), so
 *       the volume argument that keeps per-request verification successes off does not apply.
 *       The row carries the subject, the granted TTL, whether the requested TTL was clamped, the
 *       {@code jti}, and the CLAIM KEYS.</li>
 *   <li>{@link AuthAuditEvents#TOKEN_ISSUE_REJECTED} when the request carried no subject.</li>
 *   <li>{@link AuthAuditEvents#TOKEN_VERIFY_FAILED} on every rejected token, distinguishing an
 *       expired token (signature was good — an ordinary, mostly-benign event) from an invalid
 *       one (a bad signature is a <i>forgery attempt</i> or a key mismatch, and is the alert).</li>
 * </ul>
 *
 * <h2>What is recorded about a token, and what is not</h2>
 *
 * <p><b>Never the token.</b> A raw JWT in {@code audit_log} is a live bearer credential written
 * into an append-only, broadly-readable, exported table — it would outlive every rotation meant
 * to retire it, and reading the audit trail would become a privilege-escalation path.
 *
 * <p>Instead each row carries {@code tokenFingerprint}, a truncated SHA-256 of the token. That
 * is what makes the trail an incident narrative rather than a pile of unrelated rows: the
 * fingerprint on a {@code TOKEN_VERIFY_FAILED} row is the same value as on the
 * {@code TOKEN_ISSUED} row that minted it, so a rejected token can be tied to its issuance (or
 * shown never to have been issued here at all — which is the signature of a forgery). It cannot
 * be presented to anything.
 *
 * <p>Claim VALUES are not recorded either, only the sorted claim KEY set. The keys are what
 * matters for the audit question ("was this token minted carrying permissions?"); the values are
 * caller-supplied and may carry data that does not belong in an audit table.
 *
 * <p>A successful verification is not recorded: it is a per-request check on the gateway's hot
 * path, and the privileged event — the mint — is already recorded. The failures are the ones
 * with no other trace.
 */
@Service
public class JwtTokenService {

    private final JwtHelper jwtHelper;
    private final long defaultTtlSeconds;
    private final long maxTtlSeconds;
    private final AuthAuditTrail audit;

    public JwtTokenService(
            JwtHelper jwtHelper,
            @Value("${gme.auth.jwt.access-token-ttl-seconds:1800}") long defaultTtlSeconds,
            @Value("${gme.auth.jwt.max-token-ttl-seconds:3600}") long maxTtlSeconds,
            AuthAuditTrail audit) {
        this.jwtHelper = jwtHelper;
        this.defaultTtlSeconds = defaultTtlSeconds;
        this.maxTtlSeconds = maxTtlSeconds;
        this.audit = audit;
    }

    /**
     * Issues a signed token for the requested subject + claims.
     *
     * @throws ResponseStatusException 400 when the subject is missing/blank.
     */
    public IssueTokenResponse issue(IssueTokenRequest request) {
        if (request == null || request.subject() == null || request.subject().isBlank()) {
            // Recorded as a rejection (own transaction) BEFORE the throw: a refused mint attempt
            // is exactly the kind of event whose row must not be unwound by the failure path.
            audit.recordRejection(AuthAuditEvents.TOKEN, AuthAuditEvents.UNKNOWN_SUBJECT,
                    AuthAuditEvents.TOKEN_ISSUE_REJECTED,
                    AuditPayload.of().put("reason", "SUBJECT_REQUIRED").json());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subject is required");
        }
        long ttl = resolveTtl(request.ttlSeconds());
        String token = jwtHelper.issue(request.subject(), request.claims(), ttl);
        long expiresAt = Instant.now().getEpochSecond() + ttl;

        audit.record(AuthAuditEvents.TOKEN,
                AuthAuditEvents.subjectAggregate(request.subject()),
                AuthAuditEvents.TOKEN_ISSUED,
                null,
                AuditPayload.of()
                        .put("subject", request.subject())
                        .put("ttlSeconds", ttl)
                        .put("requestedTtlSeconds", request.ttlSeconds())
                        // A caller asking for a longer-than-permitted token is worth seeing in
                        // the trail even though the clamp makes it harmless.
                        .put("ttlClamped", request.ttlSeconds() != null
                                && request.ttlSeconds() > 0
                                && request.ttlSeconds() > ttl)
                        .put("expiresAt", Instant.ofEpochSecond(expiresAt))
                        .put("jti", jtiOf(token))
                        .putAll("claimKeys", claimKeys(request))
                        .put("tokenFingerprint", AuditPayload.fingerprint(token))
                        .json());

        return IssueTokenResponse.bearer(token, expiresAt);
    }

    /**
     * Verifies a token, distinguishing a bad/forged token from an expired one.
     */
    public VerifyTokenResponse verify(String token) {
        VerificationResult result = jwtHelper.verifyDetailed(token);
        return switch (result.outcome()) {
            case VALID -> VerifyTokenResponse.ok(
                    result.claims().subject(), result.claims().jti(), result.claims().exp());
            case EXPIRED -> {
                // Signature verified, so the subject in the claims is trustworthy enough to key
                // the chain by — an expired token was genuinely minted for that subject.
                audit.recordRejection(AuthAuditEvents.TOKEN,
                        AuthAuditEvents.subjectAggregate(result.claims().subject()),
                        AuthAuditEvents.TOKEN_VERIFY_FAILED,
                        AuditPayload.of()
                                .put("reason", "EXPIRED_TOKEN")
                                .put("subject", result.claims().subject())
                                .put("jti", result.claims().jti())
                                .put("exp", result.claims().exp())
                                .put("tokenFingerprint", AuditPayload.fingerprint(token))
                                .json());
                yield VerifyTokenResponse.fail("EXPIRED_TOKEN");
            }
            case INVALID -> {
                // The signature did not verify (or the token was unparseable), so ANY subject it
                // claims is untrustworthy and is not read: it goes to the shared unknown-subject
                // chain. Recording a forged token's self-asserted subject as the chain key would
                // let an attacker choose which chain their attempts landed in.
                audit.recordRejection(AuthAuditEvents.TOKEN, AuthAuditEvents.UNKNOWN_SUBJECT,
                        AuthAuditEvents.TOKEN_VERIFY_FAILED,
                        AuditPayload.of()
                                .put("reason", "INVALID_TOKEN")
                                .put("tokenFingerprint", AuditPayload.fingerprint(token))
                                .put("tokenLength", token == null ? 0 : token.length())
                                .json());
                yield VerifyTokenResponse.fail("INVALID_TOKEN");
            }
        };
    }

    /** Clamp the requested TTL into (0, maxTtlSeconds]; default when unset/non-positive. */
    private long resolveTtl(Long requested) {
        if (requested == null || requested <= 0) {
            return Math.min(defaultTtlSeconds, maxTtlSeconds);
        }
        return Math.min(requested, maxTtlSeconds);
    }

    /**
     * Read back the {@code jti} of a token we just minted, so the audit row and the token carry
     * the same correlation id. Costs one extra HMAC on a low-volume path; degrades to
     * {@code null} rather than failing the issue call, since a missing correlation id is a
     * weaker trail but a failed mint is an outage.
     */
    private String jtiOf(String token) {
        try {
            VerificationResult result = jwtHelper.verifyDetailed(token);
            return result.claims() == null ? null : result.claims().jti();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The sorted claim KEY set — never the values (see the class javadoc). Sorted so two
     * otherwise-identical mints produce identical payload bytes regardless of the incoming map's
     * iteration order.
     */
    private static java.util.Collection<String> claimKeys(IssueTokenRequest request) {
        if (request.claims() == null || request.claims().isEmpty()) {
            return java.util.List.of();
        }
        return new TreeSet<>(request.claims().keySet());
    }
}
