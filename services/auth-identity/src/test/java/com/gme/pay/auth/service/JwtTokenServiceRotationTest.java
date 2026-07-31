package com.gme.pay.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.auth.audit.AuthAuditEvents;
import com.gme.pay.auth.domain.JwtHelper;
import com.gme.pay.auth.domain.JwtKeySet;
import com.gme.pay.auth.dto.IssueTokenRequest;
import com.gme.pay.auth.dto.JwtKeySetStatusResponse;
import com.gme.pay.auth.testsupport.RecordingAuditTrail;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T0-6 (rotation half) at the service boundary: what a caller sees, what the audit trail records,
 * and what the operator endpoint reports.
 *
 * <p>The interesting assertion is the pairing of the last two. An unknown {@code kid} is answered
 * with the same {@code INVALID_TOKEN} as a forged signature — which keys this service holds is not
 * something an unauthenticated caller enumerates one probe at a time — while the audit row keeps
 * the two apart. That distinction is the difference between "somebody is trying to forge tokens"
 * and "a key was retired while its tokens were still live", and it is not recoverable after the
 * fact if both collapse into the same row.
 */
class JwtTokenServiceRotationTest {

    private static final String KEY_V1 = "rotation-test-key-v1-at-least-32-chars!!";
    private static final String KEY_V2 = "rotation-test-key-v2-at-least-32-chars!!";
    private static final Instant DEMOTED = Instant.parse("2026-07-28T09:00:00Z");

    private RecordingAuditTrail audit;

    @BeforeEach
    void setUp() {
        audit = new RecordingAuditTrail();
    }

    private JwtTokenService service(JwtKeySet keySet) {
        return new JwtTokenService(new JwtHelper(keySet, 1800), 1800, 3600, audit);
    }

    @Test
    @DisplayName("a token from the still-accepted previous key verifies through the service")
    void previousKeyTokenVerifies() {
        String minted = service(JwtKeySet.active(KEY_V1))
                .issue(new IssueTokenRequest("svc:x", null, null)).token();
        audit.clear();

        JwtTokenService afterRotation = service(
                JwtKeySet.of(KEY_V2, List.of(JwtKeySet.retiredKey(KEY_V1, DEMOTED))));

        var verified = afterRotation.verify(minted);
        assertThat(verified.valid()).isTrue();
        assertThat(verified.subject()).isEqualTo("svc:x");
        // A successful verification stays off the trail (hot path); only the mint is recorded.
        assertThat(audit.entries()).isEmpty();
    }

    @Test
    @DisplayName("an unknown kid answers INVALID_TOKEN on the wire but is audited as UNKNOWN_KID")
    void unknownKidIsAuditedDistinctly() {
        String minted = service(JwtKeySet.active(KEY_V1))
                .issue(new IssueTokenRequest("svc:victim", null, null)).token();
        audit.clear();

        // V1 fully retired — the overlap window was closed too early.
        JwtTokenService afterRetirement = service(JwtKeySet.active(KEY_V2));

        assertThat(afterRetirement.verify(minted).errorCode()).isEqualTo("INVALID_TOKEN");

        var entry = audit.only(AuthAuditEvents.TOKEN_VERIFY_FAILED);
        assertThat(entry.afterJson()).contains("\"reason\":\"UNKNOWN_KID\"");
        assertThat(entry.afterJson()).contains(JwtKeySet.kidFor(KEY_V1));
        // The kid is untrusted caller input, so it must not choose the audit chain, and the
        // token's self-asserted subject is worthless because nothing verified it.
        assertThat(entry.aggregateId()).isEqualTo(AuthAuditEvents.UNKNOWN_SUBJECT);
        assertThat(entry.mentions("svc:victim")).isFalse();
        audit.assertNeverRecorded(minted);
        assertThat(entry.rejection()).isTrue();
    }

    @Test
    @DisplayName("the key-set endpoint reports the live active kid and when the old key can be deleted")
    void keySetStatusIsTheRotationEvidence() {
        JwtTokenService svc = service(
                JwtKeySet.of(KEY_V2, List.of(JwtKeySet.retiredKey(KEY_V1, DEMOTED))));

        JwtKeySetStatusResponse status = svc.keySetStatus();

        assertThat(status.activeKid()).isEqualTo(JwtKeySet.kidFor(KEY_V2));
        assertThat(status.maxTokenTtlSeconds()).isEqualTo(3600);
        assertThat(status.keys()).hasSize(2);

        var active = status.keys().get(0);
        assertThat(active.role()).isEqualTo(JwtKeySetStatusResponse.KeyStatus.ROLE_ACTIVE);
        assertThat(active.safeToRemoveAfter()).isNull();

        var accepted = status.keys().get(1);
        assertThat(accepted.role()).isEqualTo(JwtKeySetStatusResponse.KeyStatus.ROLE_ACCEPTED);
        assertThat(accepted.kid()).isEqualTo(JwtKeySet.kidFor(KEY_V1));
        assertThat(accepted.demotedAt()).isEqualTo(DEMOTED);
        assertThat(accepted.safeToRemoveAfter()).isEqualTo(DEMOTED.plusSeconds(3600));

        // No key material anywhere in the response — only one-way thumbprints.
        assertThat(status.toString()).doesNotContain(KEY_V1).doesNotContain(KEY_V2);
    }
}
