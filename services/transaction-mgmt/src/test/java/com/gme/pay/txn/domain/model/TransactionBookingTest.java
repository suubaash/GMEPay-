package com.gme.pay.txn.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link Transaction#lockSettlementBooking(BigDecimal, String, BigDecimal)} — the
 * one-shot rate-lock applied at commit per {@code docs/MONEY_CONVENTION.md}.
 */
class TransactionBookingTest {

    private static Transaction newTxn() {
        return new Transaction("partner-acme",
                new BigDecimal("100.00"), "USD",
                new BigDecimal("130000"), "KRW");
    }

    @Test
    @DisplayName("lockSettlementBooking sets booked / mode / residual on the aggregate")
    void lockSetsFields() {
        Transaction txn = newTxn();

        txn.lockSettlementBooking(
                new BigDecimal("10500.56"), "DOWN", new BigDecimal("0.007"));

        assertNotNull(txn.bookedSettlementAmount());
        assertEquals(0, txn.bookedSettlementAmount().compareTo(new BigDecimal("10500.56")));
        assertEquals(RoundingMode.DOWN, txn.settlementRoundingMode());
        assertEquals(0, txn.roundingResidual().compareTo(new BigDecimal("0.007")));
    }

    @Test
    @DisplayName("locking twice throws IllegalStateException (booking is immutable once set)")
    void lockingTwiceThrows() {
        Transaction txn = newTxn();
        txn.lockSettlementBooking(new BigDecimal("100.00"), "HALF_UP", new BigDecimal("0.00"));

        assertThrows(IllegalStateException.class, () ->
                txn.lockSettlementBooking(new BigDecimal("200.00"), "DOWN", new BigDecimal("0.01")));
    }
}
