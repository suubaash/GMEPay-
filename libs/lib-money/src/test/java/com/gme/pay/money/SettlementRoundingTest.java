package com.gme.pay.money;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class SettlementRoundingTest {

    /** Partner books round-DOWN to 2dp: 10500.567 -> 10500.56, residual 0.007 (gain). */
    @Test
    void roundDownLeavesPositiveResidual() {
        BookedAmount b = SettlementRounding.book(new BigDecimal("10500.567"), 2, RoundingMode.DOWN);
        assertEquals(0, b.booked().compareTo(new BigDecimal("10500.56")));
        assertEquals(0, b.residual().compareTo(new BigDecimal("0.007")));
    }

    /** Proper HALF_UP to 2dp: 10500.567 -> 10500.57, residual -0.003 (loss). */
    @Test
    void halfUpCanLeaveNegativeResidual() {
        BookedAmount b = SettlementRounding.book(new BigDecimal("10500.567"), 2, RoundingMode.HALF_UP);
        assertEquals(0, b.booked().compareTo(new BigDecimal("10500.57")));
        assertEquals(0, b.residual().compareTo(new BigDecimal("-0.003")));
    }

    /** booked + residual always reconstructs the precise amount exactly. */
    @Test
    void bookedPlusResidualEqualsPrecise() {
        BigDecimal precise = new BigDecimal("123.45678");
        BookedAmount b = SettlementRounding.book(precise, 2, RoundingMode.DOWN);
        assertEquals(0, b.booked().add(b.residual()).compareTo(precise));
    }

    /** KRW (0-decimal) booking via currency scale. */
    @Test
    void krwBooksToWholeUnits() {
        BookedAmount b = SettlementRounding.book(new BigDecimal("10500.9"), "KRW", RoundingMode.DOWN);
        assertEquals(0, b.booked().compareTo(new BigDecimal("10500")));
        assertEquals(0, b.residual().compareTo(new BigDecimal("0.9")));
    }
}
