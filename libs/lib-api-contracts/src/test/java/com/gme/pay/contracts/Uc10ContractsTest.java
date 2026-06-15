package com.gme.pay.contracts;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sanity tests for the UC-10 Phase-4 Stage-1 additive DTO changes:
 * <ul>
 *   <li>{@link BalanceDeductionEntry} — new record for UC-10-01 deduction history</li>
 *   <li>{@link BalanceView} — enriched with {@code recentDeductions}; original 5-field shape
 *       preserved via {@link BalanceView#of(String, String, BigDecimal, BigDecimal, BigDecimal)}</li>
 * </ul>
 */
class Uc10ContractsTest {

    @Test
    void balanceViewOfFactoryProducesNullDeductions() {
        BalanceView view = BalanceView.of(
                "GMEK", "USD",
                new BigDecimal("5000.00"),
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"));

        assertEquals("GMEK", view.partnerCode());
        assertEquals("USD", view.currency());
        assertEquals(new BigDecimal("5000.00"), view.balance());
        assertEquals(new BigDecimal("1000.00"), view.threshold());
        assertEquals(new BigDecimal("500.00"), view.pctOfThreshold());
        // UC-10-01 additive field — null until prefunding service wires deduction history
        assertNull(view.recentDeductions());
    }

    @Test
    void balanceViewWithDeductionsCarriesEntries() {
        Instant t1 = Instant.parse("2026-06-15T02:00:00Z");
        Instant t2 = Instant.parse("2026-06-14T10:30:00Z");

        BalanceDeductionEntry e1 = new BalanceDeductionEntry(
                new BigDecimal("125.50"), t1, "TXN-1001");
        BalanceDeductionEntry e2 = new BalanceDeductionEntry(
                new BigDecimal("75.00"), t2, "TXN-1000");

        BalanceView view = new BalanceView(
                "GMEK", "USD",
                new BigDecimal("4799.50"),
                new BigDecimal("1000.00"),
                new BigDecimal("479.95"),
                List.of(e1, e2));

        assertNotNull(view.recentDeductions());
        assertEquals(2, view.recentDeductions().size());

        BalanceDeductionEntry first = view.recentDeductions().get(0);
        assertEquals(new BigDecimal("125.50"), first.amountUsd());
        assertEquals(t1, first.at());
        assertEquals("TXN-1001", first.txnRef());
    }

    @Test
    void balanceDeductionEntryHoldsAllThreeFields() {
        Instant at = Instant.parse("2026-06-15T05:00:00Z");
        BalanceDeductionEntry entry = new BalanceDeductionEntry(
                new BigDecimal("200.00"), at, "TXN-9999");

        assertEquals(new BigDecimal("200.00"), entry.amountUsd());
        assertEquals(at, entry.at());
        assertEquals("TXN-9999", entry.txnRef());
    }
}
