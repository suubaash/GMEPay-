package com.gme.pay.rbac;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gme.pay.rbac.RbacContextFilter.StampedClaims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * STRICT-mode signature verification in {@link RbacContextFilter} — the gate that decides whether
 * stamped {@code X-Gme-*} claims are trusted (#90). Exercises the decision directly with an injected
 * clock, so no servlet container / mock request is needed.
 */
class RbacContextFilterTest {

    private static final String SECRET = "edge-secret";
    private static final long SKEW = 300_000L;

    private final RbacContextFilter strict = new RbacContextFilter(SECRET, SKEW);

    /** A legitimate gateway-stamped bundle: authority claims set, constraint-context attributes absent. */
    private static StampedClaims gatewayClaims() {
        return new StampedClaims("5", "700", "report.generate,partner.view", "HUB_ADMIN",
                "AMOUNT:maxAmount=1000", null, null, null, null);
    }

    private static String sigFor(long ts, StampedClaims c) {
        return RbacClaimSigner.sign(RbacClaimSigner.canonical(ts, c.principalId(), c.tenantId(),
                c.permissions(), c.roles(), c.constraints(), c.country(), c.region(), c.office(),
                c.approvalGranted()), SECRET);
    }

    @Test
    @DisplayName("a fresh, correctly-signed bundle is trusted")
    void validSignatureTrusted() {
        long ts = 1_000_000L;
        StampedClaims c = gatewayClaims();
        assertTrue(strict.verifySignature(c, sigFor(ts, c), Long.toString(ts), ts + 5_000, "/admin/x"));
    }

    @Test
    @DisplayName("claims with no signature are refused (direct-to-service forgery)")
    void missingSignatureRefused() {
        long ts = 1_000_000L;
        StampedClaims c = gatewayClaims();
        assertFalse(strict.verifySignature(c, null, Long.toString(ts), ts, "/admin/x"));
    }

    @Test
    @DisplayName("escalated permissions under an otherwise-valid signature are refused")
    void forgedPermissionRefused() {
        long ts = 1_000_000L;
        StampedClaims signed = gatewayClaims();
        String sig = sigFor(ts, signed);                      // signed over the ORIGINAL perms
        StampedClaims tampered = new StampedClaims("5", "700", "rbac.manage", "HUB_ADMIN",
                "AMOUNT:maxAmount=1000", null, null, null, null);
        assertFalse(strict.verifySignature(tampered, sig, Long.toString(ts), ts, "/admin/x"));
    }

    @Test
    @DisplayName("BLOCKER fix: appending X-Gme-Approval-Granted to a valid bundle is refused")
    void injectedApprovalGrantedRefused() {
        long ts = 1_000_000L;
        StampedClaims signed = gatewayClaims();
        String sig = sigFor(ts, signed);                      // gateway signed approvalGranted ABSENT
        StampedClaims withApproval = new StampedClaims("5", "700", "refund.approve_l1", "HUB_ADMIN",
                "AMOUNT:maxAmount=1000", null, null, null, "true");   // attacker self-approves
        assertFalse(strict.verifySignature(withApproval, sig, Long.toString(ts), ts, "/refund/x"));
    }

    @Test
    @DisplayName("BLOCKER fix: spoofing X-Gme-Country to defeat a LOCATION constraint is refused")
    void injectedCountryRefused() {
        long ts = 1_000_000L;
        StampedClaims signed = gatewayClaims();
        String sig = sigFor(ts, signed);
        StampedClaims withCountry = new StampedClaims("5", "700", "report.generate,partner.view",
                "HUB_ADMIN", "AMOUNT:maxAmount=1000", "JP", null, null, null);
        assertFalse(strict.verifySignature(withCountry, sig, Long.toString(ts), ts, "/admin/x"));
    }

    @Test
    @DisplayName("a signature older than the skew window is refused (replay)")
    void staleSignatureRefused() {
        long ts = 1_000_000L;
        StampedClaims c = gatewayClaims();
        assertFalse(strict.verifySignature(c, sigFor(ts, c), Long.toString(ts), ts + SKEW + 1, "/admin/x"));
    }

    @Test
    @DisplayName("a malformed timestamp is refused")
    void malformedTimestampRefused() {
        assertFalse(strict.verifySignature(gatewayClaims(), "deadbeef", "not-a-number", 1L, "/admin/x"));
    }
}
