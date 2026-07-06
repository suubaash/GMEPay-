package com.gme.pay.settlement.calculator;

import com.gme.pay.settlement.calculator.MultilateralNettingCalculator.Obligation;
import com.gme.pay.settlement.calculator.MultilateralNettingCalculator.Position;
import com.gme.pay.settlement.calculator.MultilateralNettingCalculator.Summary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Plain JUnit 5 unit tests for {@link MultilateralNettingCalculator}.
 * No Spring context, no Docker, no Testcontainers.
 *
 * Covers the loop-C claim from docs/SETTLEMENT_NETTING_DESIGN.md: opposing
 * corridor flows against the SAME counterparty offset, cutting the funding
 * that must actually move, while different counterparties never cross-net.
 */
class MultilateralNettingCalculatorTest {

    private static final LocalDate VALUE_DATE = LocalDate.of(2026, 7, 5);

    private MultilateralNettingCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new MultilateralNettingCalculator();
    }

    private static BigDecimal usd(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("two-sided corridor: opposing flows against one counterparty net to the difference")
    void opposingFlowsNet() {
        // Hub owes NepalPay 10,000 for Korea→Nepal spend; NepalPay owes hub 7,500 for Nepal→Korea spend.
        Summary s = calculator.calculate(VALUE_DATE, List.of(
                new Obligation("NEPALPAY", "KR-NP", usd("10000.00")),
                new Obligation("NEPALPAY", "NP-KR", usd("-7500.00"))));

        assertEquals(1, s.positions().size());
        Position p = s.positions().get(0);
        assertEquals("NEPALPAY", p.counterparty());
        assertEquals(0, usd("17500.00").compareTo(p.grossUsd()), "gross = |10000| + |7500|");
        assertEquals(0, usd("2500.00").compareTo(p.netUsd()), "one payment of 2,500 hub→NepalPay");

        assertEquals(0, usd("17500.00").compareTo(s.grossFundingUsd()));
        assertEquals(0, usd("2500.00").compareTo(s.netFundingUsd()));
        // (17500 - 2500) / 17500 = 85.71% of funding freed by netting
        assertEquals(0, usd("85.71").compareTo(s.nettingEfficiencyPct()));
    }

    @Test
    @DisplayName("different counterparties never cross-net, even with perfectly opposing amounts")
    void noCrossCounterpartyNetting() {
        Summary s = calculator.calculate(VALUE_DATE, List.of(
                new Obligation("ZEROPAY", "KR-NP", usd("5000.00")),
                new Obligation("NEPALPAY", "NP-KR", usd("-5000.00"))));

        assertEquals(2, s.positions().size());
        assertEquals(0, usd("5000.00").compareTo(s.positions().get(0).netUsd()));
        assertEquals(0, usd("-5000.00").compareTo(s.positions().get(1).netUsd()));
        // Both legs still move in full: nothing offsets across counterparties.
        assertEquals(0, usd("10000.00").compareTo(s.netFundingUsd()));
        assertEquals(0, usd("0.00").compareTo(s.nettingEfficiencyPct()));
    }

    @Test
    @DisplayName("one-sided corridor (today's Korea-only footprint): netting frees nothing")
    void oneSidedCorridorHasNoOffset() {
        Summary s = calculator.calculate(VALUE_DATE, List.of(
                new Obligation("ZEROPAY", "KR", usd("1000.00")),
                new Obligation("ZEROPAY", "KR", usd("2000.00"))));

        assertEquals(0, usd("3000.00").compareTo(s.positions().get(0).netUsd()));
        assertEquals(0, usd("0.00").compareTo(s.nettingEfficiencyPct()),
                "same-direction flows aggregate but free no funding");
    }

    @Test
    @DisplayName("perfectly balanced two-sided corridor nets to zero — 100% efficiency")
    void perfectOffsetNetsToZero() {
        Summary s = calculator.calculate(VALUE_DATE, List.of(
                new Obligation("NEPALPAY", "KR-NP", usd("4000.00")),
                new Obligation("NEPALPAY", "NP-KR", usd("-4000.00"))));

        assertEquals(0, BigDecimal.ZERO.setScale(2).compareTo(s.positions().get(0).netUsd()));
        assertEquals(0, usd("100.00").compareTo(s.nettingEfficiencyPct()));
    }

    @Test
    @DisplayName("empty window: zero totals and null efficiency (nothing to net, not 100%)")
    void emptyWindow() {
        Summary s = calculator.calculate(VALUE_DATE, List.of());
        assertEquals(0, s.positions().size());
        assertEquals(0, BigDecimal.ZERO.setScale(2).compareTo(s.grossFundingUsd()));
        assertEquals(0, BigDecimal.ZERO.setScale(2).compareTo(s.netFundingUsd()));
        assertNull(s.nettingEfficiencyPct(), "no obligations -> efficiency is unmeasured, not 100%");
    }

    @Test
    @DisplayName("USD amounts round HALF_UP to scale 2 per MONEY_CONVENTION")
    void roundsHalfUpToUsdScale() {
        Summary s = calculator.calculate(VALUE_DATE, List.of(
                new Obligation("NEPALPAY", "KR-NP", usd("0.005")),
                new Obligation("NEPALPAY", "KR-NP", usd("0.004"))));
        assertEquals(0, usd("0.01").compareTo(s.positions().get(0).netUsd()));
    }

    @Test
    @DisplayName("blank counterparty or null amount is rejected loudly")
    void validatesObligations() {
        assertThrows(IllegalArgumentException.class,
                () -> new Obligation(" ", "KR-NP", usd("1.00")));
        assertThrows(IllegalArgumentException.class,
                () -> new Obligation("NEPALPAY", "KR-NP", null));
    }
}
