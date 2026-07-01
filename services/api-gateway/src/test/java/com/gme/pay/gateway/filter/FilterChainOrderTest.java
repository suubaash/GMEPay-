package com.gme.pay.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts the documented execution order of the gateway's GlobalFilter chain.
 *
 * <p>The order constants are the single source of truth — all other ordering
 * documentation (Javadoc, ADRs) refers to these. A compile-time assertion here
 * catches any accidental renumbering.
 *
 * <p>Declared filter chain (lower order = runs earlier):
 * <pre>
 *   ORDER 2  — PartnerIpAllowlistFilter  (IP check before signature surface)
 *   ORDER 3  — MtlsFingerprintFilter     (cert binding before body buffering)
 *   ORDER 4  — HmacSignatureFilter       (HMAC-SHA256 signature + timestamp expiry)
 *   ORDER 5  — ReplayProtectionFilter    (per-partner nonce)
 *   ORDER 6  — RateLimitFilter           (per-partner per-second throttle)
 *   ORDER 7  — IdempotencyKeyFilter      (POST idempotency-key validation)
 * </pre>
 */
class FilterChainOrderTest {

    @Test
    @DisplayName("PartnerIpAllowlistFilter runs before MtlsFingerprintFilter")
    void ipAllowlist_before_mtls() {
        assertTrue(PartnerIpAllowlistFilter.ORDER < MtlsFingerprintFilter.ORDER,
                "PartnerIpAllowlistFilter (" + PartnerIpAllowlistFilter.ORDER
                        + ") must run before MtlsFingerprintFilter ("
                        + MtlsFingerprintFilter.ORDER + ")");
    }

    @Test
    @DisplayName("MtlsFingerprintFilter runs before HmacSignatureFilter")
    void mtls_before_hmac() {
        assertTrue(MtlsFingerprintFilter.ORDER < HmacSignatureFilter.ORDER,
                "MtlsFingerprintFilter (" + MtlsFingerprintFilter.ORDER
                        + ") must run before HmacSignatureFilter ("
                        + HmacSignatureFilter.ORDER + ")");
    }

    @Test
    @DisplayName("PartnerIpAllowlistFilter runs before HmacSignatureFilter")
    void ipAllowlist_before_hmac() {
        assertTrue(PartnerIpAllowlistFilter.ORDER < HmacSignatureFilter.ORDER,
                "PartnerIpAllowlistFilter (" + PartnerIpAllowlistFilter.ORDER
                        + ") must run before HmacSignatureFilter ("
                        + HmacSignatureFilter.ORDER + ")");
    }

    @Test
    @DisplayName("HmacSignatureFilter runs before ReplayProtectionFilter before IdempotencyKeyFilter")
    void hmac_before_replay_before_idempotency() {
        assertTrue(HmacSignatureFilter.ORDER < ReplayProtectionFilter.ORDER,
                "HmacSignatureFilter (" + HmacSignatureFilter.ORDER
                        + ") must run before ReplayProtectionFilter (" + ReplayProtectionFilter.ORDER + ")");
        assertTrue(ReplayProtectionFilter.ORDER < IdempotencyKeyFilter.ORDER,
                "ReplayProtectionFilter (" + ReplayProtectionFilter.ORDER
                        + ") must run before IdempotencyKeyFilter (" + IdempotencyKeyFilter.ORDER + ")");
    }

    @Test
    @DisplayName("RateLimitFilter runs after ReplayProtectionFilter and before IdempotencyKeyFilter")
    void replay_before_rateLimit_before_idempotency() {
        assertTrue(ReplayProtectionFilter.ORDER < RateLimitFilter.ORDER,
                "ReplayProtectionFilter (" + ReplayProtectionFilter.ORDER
                        + ") must run before RateLimitFilter (" + RateLimitFilter.ORDER + ")");
        assertTrue(RateLimitFilter.ORDER < IdempotencyKeyFilter.ORDER,
                "RateLimitFilter (" + RateLimitFilter.ORDER
                        + ") must run before IdempotencyKeyFilter (" + IdempotencyKeyFilter.ORDER + ")");
    }

    @Test
    @DisplayName("All filter order constants have the expected values")
    void absoluteOrderValues_matchDocumentation() {
        org.junit.jupiter.api.Assertions.assertAll(
                () -> org.junit.jupiter.api.Assertions.assertEquals(2, PartnerIpAllowlistFilter.ORDER,
                        "PartnerIpAllowlistFilter must be ORDER 2"),
                () -> org.junit.jupiter.api.Assertions.assertEquals(3, MtlsFingerprintFilter.ORDER,
                        "MtlsFingerprintFilter must be ORDER 3"),
                () -> org.junit.jupiter.api.Assertions.assertEquals(4, HmacSignatureFilter.ORDER,
                        "HmacSignatureFilter must be ORDER 4"),
                () -> org.junit.jupiter.api.Assertions.assertEquals(5, ReplayProtectionFilter.ORDER,
                        "ReplayProtectionFilter must be ORDER 5"),
                () -> org.junit.jupiter.api.Assertions.assertEquals(6, RateLimitFilter.ORDER,
                        "RateLimitFilter must be ORDER 6"),
                () -> org.junit.jupiter.api.Assertions.assertEquals(7, IdempotencyKeyFilter.ORDER,
                        "IdempotencyKeyFilter must be ORDER 7")
        );
    }
}
