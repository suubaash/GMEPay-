package com.gme.pay.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

/**
 * Sanity tests for the {@link Partner} record. After the Slice 1 schism resolution
 * the record carries {@code (Long partnerId, String partnerCode, PartnerType, String,
 * RoundingMode)} — these tests exercise the rounding-mode default policy plus the
 * legacy-friendly {@link Partner#of(String, PartnerType, String)} factory that leaves
 * the surrogate id {@code null} during the Expand phase.
 */
class PartnerTest {

    @Test
    void defaultRoundingIsHalfUp() {
        Partner p = Partner.of("GMEREMIT", PartnerType.LOCAL, "KRW");
        assertEquals(RoundingMode.HALF_UP, p.settlementRoundingMode());
    }

    @Test
    void legacyFactoryLeavesSurrogateIdNull() {
        Partner p = Partner.of("GMEREMIT", PartnerType.LOCAL, "KRW");
        assertNull(p.partnerId(),
                "the legacy factory must leave partnerId null until config-registry's Expand"
                        + " backfill issues a surrogate");
        assertEquals("GMEREMIT", p.partnerCode());
    }

    @Test
    void roundDownPartnerIsConfigurable() {
        Partner p = Partner.of("SENDMN", PartnerType.OVERSEAS, "USD", RoundingMode.DOWN);
        assertEquals(RoundingMode.DOWN, p.settlementRoundingMode());
    }

    @Test
    void nullModeFallsBackToHalfUp() {
        Partner p = Partner.of("TBANK", PartnerType.OVERSEAS, "USD", null);
        assertEquals(RoundingMode.HALF_UP, p.settlementRoundingMode());
    }

    @Test
    void canonicalCtorCarriesBothIds() {
        Partner p = new Partner(42L, "TBANK", PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP);
        assertNotNull(p.partnerId());
        assertEquals(42L, p.partnerId());
        assertEquals("TBANK", p.partnerCode());
    }
}
