package com.gme.pay.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class PartnerTest {

    @Test
    void defaultRoundingIsHalfUp() {
        Partner p = Partner.of("GMEREMIT", PartnerType.LOCAL, "KRW");
        assertEquals(RoundingMode.HALF_UP, p.settlementRoundingMode());
    }

    @Test
    void roundDownPartnerIsConfigurable() {
        Partner p = new Partner("SENDMN", PartnerType.OVERSEAS, "USD", RoundingMode.DOWN);
        assertEquals(RoundingMode.DOWN, p.settlementRoundingMode());
    }

    @Test
    void nullModeFallsBackToHalfUp() {
        Partner p = new Partner("TBANK", PartnerType.OVERSEAS, "USD", null);
        assertEquals(RoundingMode.HALF_UP, p.settlementRoundingMode());
    }
}
