package com.gme.pay.auth.service;

import com.gme.pay.auth.domain.JwtHelper;
import com.gme.pay.auth.domain.JwtHelper.VerificationResult;
import com.gme.pay.auth.dto.IssueTokenRequest;
import com.gme.pay.auth.dto.IssueTokenResponse;
import com.gme.pay.auth.dto.VerifyTokenResponse;
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
 */
@Service
public class JwtTokenService {

    private final JwtHelper jwtHelper;
    private final long defaultTtlSeconds;
    private final long maxTtlSeconds;

    public JwtTokenService(
            JwtHelper jwtHelper,
            @Value("${gme.auth.jwt.access-token-ttl-seconds:1800}") long defaultTtlSeconds,
            @Value("${gme.auth.jwt.max-token-ttl-seconds:3600}") long maxTtlSeconds) {
        this.jwtHelper = jwtHelper;
        this.defaultTtlSeconds = defaultTtlSeconds;
        this.maxTtlSeconds = maxTtlSeconds;
    }

    /**
     * Issues a signed token for the requested subject + claims.
     *
     * @throws ResponseStatusException 400 when the subject is missing/blank.
     */
    public IssueTokenResponse issue(IssueTokenRequest request) {
        if (request == null || request.subject() == null || request.subject().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subject is required");
        }
        long ttl = resolveTtl(request.ttlSeconds());
        String token = jwtHelper.issue(request.subject(), request.claims(), ttl);
        long expiresAt = Instant.now().getEpochSecond() + ttl;
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
            case EXPIRED -> VerifyTokenResponse.fail("EXPIRED_TOKEN");
            case INVALID -> VerifyTokenResponse.fail("INVALID_TOKEN");
        };
    }

    /** Clamp the requested TTL into (0, maxTtlSeconds]; default when unset/non-positive. */
    private long resolveTtl(Long requested) {
        if (requested == null || requested <= 0) {
            return Math.min(defaultTtlSeconds, maxTtlSeconds);
        }
        return Math.min(requested, maxTtlSeconds);
    }
}
