package com.gme.pay.payment.domain.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class SettlementBookingServiceTest {

    private final SettlementBookingService svc = new SettlementBookingService(new StubPartnerConfigClient());

    @Test
    void roundDownPartnerFloorsAndLeavesPositiveResidual() {
        // partner 1002 = DOWN, USD (2dp)
        SettlementBooking b = svc.book(1002L, new BigDecimal("125.567"), "USD");
        assertEquals(RoundingMode.DOWN, b.mode());
        assertEquals(0, b.booked().compareTo(new BigDecimal("125.56")), "booked");
        assertEquals(0, b.residual().compareTo(new BigDecimal("0.007")), "residual");
    }

    @Test
    void defaultPartnerUsesHalfUp() {
        // partner 1001 = HALF_UP, KRW (0dp): 125.565 -> 126
        SettlementBooking b = svc.book(1001L, new BigDecimal("125.565"), "KRW");
        assertEquals(RoundingMode.HALF_UP, b.mode());
        assertEquals(0, b.booked().compareTo(new BigDecimal("126")));
    }

    @Test
    void bookedPlusResidualEqualsPrecise() {
        SettlementBooking b = svc.book(1002L, new BigDecimal("999.999"), "USD");
        assertEquals(0, b.booked().add(b.residual()).compareTo(new BigDecimal("999.999")));
    }
}
