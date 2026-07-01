package com.gme.pay.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.auth.domain.JwtHelper;
import com.gme.pay.auth.dto.IssueTokenRequest;
import com.gme.pay.auth.dto.IssueTokenResponse;
import com.gme.pay.auth.dto.VerifyTokenResponse;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pure-unit tests for {@link JwtTokenService} (no Spring context). Covers the
 * accept / reject / expiry decision matrix for the internal HS256 token surface
 * plus TTL clamping and subject validation.
 */
class JwtTokenServiceTest {

    private static final String SECRET = "unit-test-signing-secret-at-least-32-chars!!";

    /** Service with default TTL 1800s, max 3600s. */
    private JwtTokenService service(long defaultTtl, long maxTtl) {
        return new JwtTokenService(new JwtHelper(SECRET, defaultTtl), defaultTtl, maxTtl);
    }

    @Test
    void issueThenVerify_validToken_isAccepted() {
        JwtTokenService svc = service(1800, 3600);
        IssueTokenResponse issued = svc.issue(
                new IssueTokenRequest("svc:config-registry",
                        Map.of("role_code", "SERVICE", "partner_id", 42), null));

        assertThat(issued.tokenType()).isEqualTo("Bearer");
        assertThat(issued.expiresAt()).isGreaterThan(Instant.now().getEpochSecond());
        assertThat(issued.toString()).contains("REDACTED").doesNotContain(issued.token());

        VerifyTokenResponse verified = svc.verify(issued.token());
        assertThat(verified.valid()).isTrue();
        assertThat(verified.subject()).isEqualTo("svc:config-registry");
        assertThat(verified.jti()).isNotBlank();
        assertThat(verified.errorCode()).isNull();
    }

    @Test
    void verify_tamperedSignature_isRejectedAsInvalid() {
        JwtTokenService svc = service(1800, 3600);
        String token = svc.issue(new IssueTokenRequest("svc:x", null, null)).token();
        // Flip the last character of the signature segment.
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");

        VerifyTokenResponse verified = svc.verify(tampered);
        assertThat(verified.valid()).isFalse();
        assertThat(verified.errorCode()).isEqualTo("INVALID_TOKEN");
        assertThat(verified.subject()).isNull();
    }

    @Test
    void verify_tokenSignedWithDifferentSecret_isRejected() {
        JwtTokenService issuer = service(1800, 3600);
        String token = issuer.issue(new IssueTokenRequest("svc:x", null, null)).token();

        JwtTokenService otherVerifier = new JwtTokenService(
                new JwtHelper("a-totally-different-secret-also-32-chars-long", 1800), 1800, 3600);
        assertThat(otherVerifier.verify(token).errorCode()).isEqualTo("INVALID_TOKEN");
    }

    @Test
    void verify_expiredToken_isRejectedAsExpired() {
        // Negative default TTL → exp is already in the past at issue time.
        JwtTokenService svc = new JwtTokenService(new JwtHelper(SECRET, -10), -10, 3600);
        String token = svc.issue(new IssueTokenRequest("svc:x", null, null)).token();

        VerifyTokenResponse verified = svc.verify(token);
        assertThat(verified.valid()).isFalse();
        assertThat(verified.errorCode()).isEqualTo("EXPIRED_TOKEN");
    }

    @Test
    void verify_garbageAndNull_isRejected() {
        JwtTokenService svc = service(1800, 3600);
        assertThat(svc.verify(null).errorCode()).isEqualTo("INVALID_TOKEN");
        assertThat(svc.verify("").errorCode()).isEqualTo("INVALID_TOKEN");
        assertThat(svc.verify("not.a.jwt").errorCode()).isEqualTo("INVALID_TOKEN");
        assertThat(svc.verify("only-one-segment").errorCode()).isEqualTo("INVALID_TOKEN");
    }

    @Test
    void issue_clampsRequestedTtlToMaximum() {
        JwtTokenService svc = service(1800, 3600);
        long before = Instant.now().getEpochSecond();
        // Request a 1-day TTL; max is 3600s → exp must be clamped near now+3600.
        IssueTokenResponse issued =
                svc.issue(new IssueTokenRequest("svc:x", null, 86_400L));
        assertThat(issued.expiresAt()).isBetween(before + 3600 - 5, before + 3600 + 5);
    }

    @Test
    void issue_honoursShorterRequestedTtl() {
        JwtTokenService svc = service(1800, 3600);
        long before = Instant.now().getEpochSecond();
        IssueTokenResponse issued =
                svc.issue(new IssueTokenRequest("svc:x", null, 60L));
        assertThat(issued.expiresAt()).isBetween(before + 60 - 5, before + 60 + 5);
    }

    @Test
    void issue_missingSubject_is400() {
        JwtTokenService svc = service(1800, 3600);
        assertThatThrownBy(() -> svc.issue(new IssueTokenRequest(null, null, null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> svc.issue(new IssueTokenRequest("  ", null, null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> svc.issue(null))
                .isInstanceOf(ResponseStatusException.class);
    }
}
