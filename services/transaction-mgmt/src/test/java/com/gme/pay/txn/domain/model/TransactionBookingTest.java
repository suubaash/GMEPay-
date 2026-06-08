package com.gme.pay.txn.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TransactionBookingTest {

    private Transaction txn() {
        return new Transaction("P1001", new BigDecimal("100"), "USD", new BigDecimal("130000"), "KRW");
    }

    @Test
    void lockSetsBookingFields() {
        Transaction t = txn();
        t.lockSettlementBooking(new BigDecimal("125.56"), "DOWN", new BigDecimal("0.007"));
        assertEquals(0, t.bookedSettlementAmount().compareTo(new BigDecimal("125.56")));
        assertEquals("DOWN", t.settlementRoundingMode());
        assertEquals(0, t.roundingResidual().compareTo(new BigDecimal("0.007")));
    }

    @Test
    void lockingTwiceThrows() {
        Transaction t = txn();
        t.lockSettlementBooking(new BigDecimal("1"), "HALF_UP", BigDecimal.ZERO);
        assertThrows(IllegalStateException.class,
                () -> t.lockSettlementBooking(new BigDecimal("2"), "DOWN", BigDecimal.ZERO));
    }
}
