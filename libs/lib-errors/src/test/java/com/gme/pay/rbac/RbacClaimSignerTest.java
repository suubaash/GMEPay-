package com.gme.pay.rbac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** HMAC provenance signing/verification — the crypto behind the anti-spoof fix (#90). */
class RbacClaimSignerTest {

    private static final String SECRET = "edge-secret";

    private static String canonical(long ts, String perms, String country, String approval) {
        return RbacClaimSigner.canonical(ts, "5", "700", perms, "HUB_ADMIN",
                "AMOUNT:maxAmount=1000", country, "KOREA", "SEOUL-1", approval);
    }

    @Test
    @DisplayName("sign is deterministic, verifies, and is a 256-bit hex digest")
    void signDeterministicAndVerifies() {
        String c = canonical(1000L, "report.generate,partner.view", null, null);
        String sig = RbacClaimSigner.sign(c, SECRET);
        assertEquals(sig, RbacClaimSigner.sign(c, SECRET));   // deterministic
        assertEquals(64, sig.length());                        // 32 bytes -> 64 hex chars
        assertTrue(RbacClaimSigner.verify(sig, c, SECRET));
    }

    @Test
    @DisplayName("a signature made with a different secret does not verify")
    void wrongSecretFails() {
        String c = canonical(1000L, "partner.view", null, null);
        assertFalse(RbacClaimSigner.verify(RbacClaimSigner.sign(c, SECRET), c, "attacker-secret"));
    }

    @Test
    @DisplayName("escalating the permissions claim invalidates the signature")
    void tamperedPermissionFails() {
        String signed = canonical(1000L, "partner.view", null, null);
        String sig = RbacClaimSigner.sign(signed, SECRET);
        String forged = canonical(1000L, "rbac.manage", null, null);   // escalate
        assertFalse(RbacClaimSigner.verify(sig, forged, SECRET));
    }

    @Test
    @DisplayName("injecting an approval-granted / country attribute invalidates the signature (BLOCKER fix)")
    void tamperedConstraintContextFails() {
        String signed = canonical(1000L, "refund.approve_l1", null, null);     // gateway signs these absent
        String sig = RbacClaimSigner.sign(signed, SECRET);
        assertFalse(RbacClaimSigner.verify(sig, canonical(1000L, "refund.approve_l1", null, "true"), SECRET));
        assertFalse(RbacClaimSigner.verify(sig, canonical(1000L, "refund.approve_l1", "JP", null), SECRET));
    }

    @Test
    @DisplayName("null and empty-string claims canonicalise identically (gateway-removed vs absent)")
    void nullClaimsNormaliseConsistently() {
        assertEquals(
                RbacClaimSigner.canonical(1L, "5", null, "p", "r", null, null, null, null, null),
                RbacClaimSigner.canonical(1L, "5", "",   "p", "r", "",   "",   "",   "",   ""));
    }

    @Test
    @DisplayName("null / blank candidate signature is rejected")
    void emptyOrNullSignatureRejected() {
        String c = canonical(1L, "p", null, null);
        assertFalse(RbacClaimSigner.verify(null, c, SECRET));
        assertFalse(RbacClaimSigner.verify("", c, SECRET));
        assertFalse(RbacClaimSigner.verify("   ", c, SECRET));
    }
}
