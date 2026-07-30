package com.gme.pay.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.auth.audit.AuthAuditEvents;
import com.gme.pay.auth.domain.JwtHelper;
import com.gme.pay.auth.dto.IssueTokenRequest;
import com.gme.pay.auth.dto.IssueTokenResponse;
import com.gme.pay.auth.testsupport.RecordingAuditTrail;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * T5-1 / CISO §9 — token issuance is the single most consequential operation in this service (a
 * token minted here makes its bearer whoever the {@code sub} claim says, to every service that
 * trusts a GME signature) and it previously emitted nothing at all.
 *
 * <p>The load-bearing assertion in this class is that <b>the raw token never reaches an audit
 * row</b>. A JWT in {@code audit_log} would be a live bearer credential written into an
 * append-only, exported table: it would outlive every rotation meant to retire it, and reading
 * the audit trail would become a privilege-escalation path. What is stored instead is a truncated
 * SHA-256 fingerprint, which is what lets a rejected token be tied back to the row that minted it
 * — or shown never to have been minted here, which is the signature of a forgery.
 */
class JwtTokenServiceAuditTest {

    private static final String SECRET = "unit-test-signing-secret-at-least-32-chars!!";

    private RecordingAuditTrail audit;

    @BeforeEach
    void setUp() {
        audit = new RecordingAuditTrail();
    }

    private JwtTokenService service(long defaultTtl, long maxTtl) {
        return new JwtTokenService(new JwtHelper(SECRET, defaultTtl), defaultTtl, maxTtl, audit);
    }

    @Test
    @DisplayName("issue writes TOKEN_ISSUED on the subject's chain — never the token itself")
    void issue_isAudited_withoutTheToken() {
        IssueTokenResponse issued = service(1800, 3600).issue(new IssueTokenRequest(
                "svc:config-registry", Map.of("permissions", "rbac.manage", "partner_id", 42),
                null));

        var entry = audit.only(AuthAuditEvents.TOKEN_ISSUED);
        assertThat(entry.aggregateType()).isEqualTo(AuthAuditEvents.TOKEN);
        assertThat(entry.aggregateId()).isEqualTo("sub:svc:config-registry");
        assertThat(entry.rejection()).isFalse();
        assertThat(entry.afterJson())
                .contains("\"subject\":\"svc:config-registry\"")
                .contains("\"ttlSeconds\":1800")
                .contains("\"jti\":\"");

        // THE assertion: the bearer credential is absent, and only a fingerprint stands in for it.
        audit.assertNeverRecorded(issued.token());
        assertThat(entry.afterJson()).contains("\"tokenFingerprint\":\"sha256:");

        // Claim KEYS are recorded (the audit question is "was this token minted carrying
        // permissions?"); claim VALUES are not, since they are caller-supplied.
        assertThat(entry.afterJson()).contains("\"claimKeys\":[\"partner_id\",\"permissions\"]");
        assertThat(entry.mentions("rbac.manage")).isFalse();
    }

    @Test
    @DisplayName("a clamped TTL request is visible in the row even though the clamp made it safe")
    void issue_recordsThatTheRequestedTtlWasClamped() {
        service(1800, 3600).issue(new IssueTokenRequest("svc:greedy", null, 86_400L));

        var entry = audit.only(AuthAuditEvents.TOKEN_ISSUED);
        assertThat(entry.afterJson())
                .contains("\"requestedTtlSeconds\":86400")
                .contains("\"ttlSeconds\":3600")
                .contains("\"ttlClamped\":true");
    }

    @Test
    @DisplayName("a refused mint attempt is audited on the rejection path, before the throw")
    void issue_withoutSubject_isAuditedAsARejection() {
        JwtTokenService svc = service(1800, 3600);
        assertThatThrownBy(() -> svc.issue(new IssueTokenRequest(null, null, null)))
                .isInstanceOf(ResponseStatusException.class);

        var entry = audit.only(AuthAuditEvents.TOKEN_ISSUE_REJECTED);
        assertThat(entry.aggregateId()).isEqualTo(AuthAuditEvents.UNKNOWN_SUBJECT);
        assertThat(entry.afterJson()).contains("\"reason\":\"SUBJECT_REQUIRED\"");
        // The method throws immediately afterwards, so this row only exists at all because it is
        // written on the own-transaction path.
        assertThat(entry.rejection()).isTrue();
    }

    @Test
    @DisplayName("a forged token is audited on the shared unknown-subject chain, not its own claim")
    void verify_forgedToken_doesNotLetTheAttackerChooseTheChain() {
        JwtTokenService svc = service(1800, 3600);
        String token = svc.issue(new IssueTokenRequest("svc:victim", null, null)).token();
        audit.clear();

        int sigStart = token.lastIndexOf('.') + 1;
        char first = token.charAt(sigStart);
        String forged = token.substring(0, sigStart) + (first == 'A' ? 'B' : 'A')
                + token.substring(sigStart + 1);

        assertThat(svc.verify(forged).errorCode()).isEqualTo("INVALID_TOKEN");

        var entry = audit.only(AuthAuditEvents.TOKEN_VERIFY_FAILED);
        // The signature did not verify, so the token's self-asserted subject is worthless: it must
        // NOT be used as the chain key, or an attacker would choose which chain their attempts
        // landed in (and could bury them in a busy legitimate chain).
        assertThat(entry.aggregateId()).isEqualTo(AuthAuditEvents.UNKNOWN_SUBJECT);
        assertThat(entry.afterJson()).contains("\"reason\":\"INVALID_TOKEN\"");
        assertThat(entry.mentions("svc:victim")).isFalse();
        audit.assertNeverRecorded(forged, token);
        assertThat(entry.rejection()).isTrue();
    }

    @Test
    @DisplayName("an expired token IS keyed by its subject — the signature was genuine")
    void verify_expiredToken_isAuditedAgainstItsSubject() {
        // Negative TTL → already expired at issue time.
        JwtTokenService svc = new JwtTokenService(new JwtHelper(SECRET, -10), -10, 3600, audit);
        String token = svc.issue(new IssueTokenRequest("svc:stale", null, null)).token();
        audit.clear();

        assertThat(svc.verify(token).errorCode()).isEqualTo("EXPIRED_TOKEN");

        var entry = audit.only(AuthAuditEvents.TOKEN_VERIFY_FAILED);
        assertThat(entry.aggregateId()).isEqualTo("sub:svc:stale");
        assertThat(entry.afterJson()).contains("\"reason\":\"EXPIRED_TOKEN\"");
        audit.assertNeverRecorded(token);
    }

    @Test
    @DisplayName("garbage input is audited without exploding on a null token")
    void verify_garbage_isAudited() {
        JwtTokenService svc = service(1800, 3600);
        svc.verify(null);
        svc.verify("");
        svc.verify("not.a.jwt");

        assertThat(audit.ofType(AuthAuditEvents.TOKEN_VERIFY_FAILED)).hasSize(3);
        // fingerprint(null/blank) is null and is therefore DROPPED from the payload rather than
        // becoming a constant fingerprint-of-the-empty-string that would read as if something had
        // been presented.
        assertThat(audit.ofType(AuthAuditEvents.TOKEN_VERIFY_FAILED).get(0).afterJson())
                .doesNotContain("tokenFingerprint")
                .contains("\"tokenLength\":0");
    }

    @Test
    @DisplayName("a successful verification writes no row (the mint is the audited event)")
    void verify_validToken_isNotAudited() {
        JwtTokenService svc = service(1800, 3600);
        String token = svc.issue(new IssueTokenRequest("svc:ok", null, null)).token();
        audit.clear();

        assertThat(svc.verify(token).valid()).isTrue();
        assertThat(audit.entries()).isEmpty();
    }
}
