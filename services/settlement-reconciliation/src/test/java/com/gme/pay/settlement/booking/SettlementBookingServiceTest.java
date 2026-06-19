package com.gme.pay.settlement.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gme.pay.money.BookedAmount;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Spec Addendum 001 — per-partner settlement rounding (the heart of the outbound spine). Mirrors the
 * lib-money worked examples and proves the partner's configured mode is threaded (not a fixed HALF_UP).
 */
class SettlementBookingServiceTest {

    private final SettlementBookingService booking = new SettlementBookingService();

    @Test
    @DisplayName("KRW (scale 0) DOWN: 10500.9 books to 10500, residual 0.9")
    void krwDownScale0() {
        BookedAmount ba = booking.book("KRW", RoundingMode.DOWN, new BigDecimal("10500.9"), 'N');
        assertEquals(0, ba.booked().compareTo(new BigDecimal("10500")), "booked");
        assertEquals(0, ba.residual().compareTo(new BigDecimal("0.9")), "residual = precise - booked");
        assertEquals(0, ba.scale());
    }

    @Test
    @DisplayName("scale-2 ccy DOWN vs HALF_UP: 10500.567 → 10500.56 (+0.007) vs 10500.57 (-0.003)")
    void scale2DownVsHalfUp() {
        BookedAmount down = booking.book("USD", RoundingMode.DOWN, new BigDecimal("10500.567"), 'N');
        assertEquals(0, down.booked().compareTo(new BigDecimal("10500.56")));
        assertEquals(0, down.residual().compareTo(new BigDecimal("0.007")));

        BookedAmount half = booking.book("USD", RoundingMode.HALF_UP, new BigDecimal("10500.567"), 'N');
        assertEquals(0, half.booked().compareTo(new BigDecimal("10500.57")));
        assertEquals(0, half.residual().compareTo(new BigDecimal("-0.003")));
    }

    @Test
    @DisplayName("per-partner mode is threaded: FLOOR vs HALF_UP on 10500.9 KRW differ")
    void partnerModeThreaded() {
        BigDecimal precise = new BigDecimal("10500.9");
        assertEquals(0, booking.book("KRW", RoundingMode.FLOOR, precise, 'N').booked().compareTo(new BigDecimal("10500")));
        assertEquals(0, booking.book("KRW", RoundingMode.HALF_UP, precise, 'N').booked().compareTo(new BigDecimal("10501")));
    }

    @Test
    @DisplayName("null mode defaults to HALF_UP (Addendum default)")
    void nullModeDefaultsHalfUp() {
        assertEquals(0, booking.book("KRW", null, new BigDecimal("10500.9"), 'N').booked().compareTo(new BigDecimal("10501")));
    }

    @Test
    @DisplayName("GROSS net < 0 (refunds > payments) throws NegativeSettlementAmountException (7.6-T04)")
    void grossNegativeRejected() {
        assertThrows(NegativeSettlementAmountException.class,
                () -> booking.book("KRW", RoundingMode.HALF_UP, new BigDecimal("-100"), 'G'));
    }

    @Test
    @DisplayName("NET net < 0 is allowed (a domestic merchant can owe a net refund position)")
    void netNegativeAllowed() {
        BookedAmount ba = booking.book("KRW", RoundingMode.HALF_UP, new BigDecimal("-100"), 'N');
        assertEquals(0, ba.booked().compareTo(new BigDecimal("-100")));
    }
}
