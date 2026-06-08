package com.gme.pay.settlement.calculator;

import com.gme.pay.settlement.model.TransactionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 unit tests for {@link GrossSettlementAmountCalculator}.
 * No Spring context, no Docker, no Testcontainers.
 *
 * Spec reference: 7.1-T06 acceptance checks.
 */
class GrossSettlementAmountCalculatorTest {

    private GrossSettlementAmountCalculator calculator;
    private static final LocalDate SETTLEMENT_DATE = LocalDate.of(2026, 6, 8);
    private static final String MERCHANT_M2 = "M2";

    @BeforeEach
    void setUp() {
        calculator = new GrossSettlementAmountCalculator();
    }

    // -----------------------------------------------------------------------
    // Spec check: given 3 OVERSEAS transactions for merchant M2 with
    // target_payout 50000, 30000, 20000 KRW:
    //   gross_txn_amount=100000, merchant_fee_total=0, net_settlement_amount=100000
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("GROSS: three transactions -> net == gross, fee == 0")
    void threeTransactions_grossEqualsNet_feeZero() {
        List<TransactionRecord> txns = List.of(
                grossTxn("TXN-G1", MERCHANT_M2, "50000"),
                grossTxn("TXN-G2", MERCHANT_M2, "30000"),
                grossTxn("TXN-G3", MERCHANT_M2, "20000")
        );

        GrossSettlementSummary summary = calculator.calculate(MERCHANT_M2, SETTLEMENT_DATE, txns);

        assertEquals(MERCHANT_M2, summary.merchantId());
        assertEquals(3, summary.grossTxnCount());
        assertEquals(new BigDecimal("100000"), summary.grossTxnAmount());
        assertEquals(BigDecimal.ZERO, summary.merchantFeeTotal());
        assertEquals(new BigDecimal("100000"), summary.netSettlementAmount());
        assertEquals('G', summary.settlementType());
    }

    @Test
    @DisplayName("GROSS: net_settlement_amount always equals gross_txn_amount")
    void netAlwaysEqualsGross() {
        List<TransactionRecord> txns = List.of(
                grossTxn("TXN-G4", MERCHANT_M2, "77777")
        );

        GrossSettlementSummary summary = calculator.calculate(MERCHANT_M2, SETTLEMENT_DATE, txns);

        assertEquals(0, summary.grossTxnAmount().compareTo(summary.netSettlementAmount()),
                "net_settlement_amount must equal gross_txn_amount for GROSS settlement");
    }

    @Test
    @DisplayName("GROSS: empty transaction list returns zero summary without error")
    void emptyTransactions_returnsZeroSummary() {
        GrossSettlementSummary summary = calculator.calculate(MERCHANT_M2, SETTLEMENT_DATE, List.of());

        assertEquals(0, summary.grossTxnCount());
        assertEquals(BigDecimal.ZERO, summary.grossTxnAmount());
        assertEquals(BigDecimal.ZERO, summary.merchantFeeTotal());
        assertEquals(BigDecimal.ZERO, summary.netSettlementAmount());
    }

    @Test
    @DisplayName("GROSS: null transaction list returns zero summary without error")
    void nullTransactions_returnsZeroSummary() {
        GrossSettlementSummary summary = calculator.calculate(MERCHANT_M2, SETTLEMENT_DATE, null);

        assertEquals(0, summary.grossTxnCount());
        assertEquals(BigDecimal.ZERO, summary.netSettlementAmount());
    }

    @Test
    @DisplayName("GROSS: NET transaction in input throws IllegalArgumentException")
    void netTransactionInInput_throws() {
        List<TransactionRecord> txns = List.of(
                netTxn("TXN-N1", MERCHANT_M2, "15000", "0.008")
        );

        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(MERCHANT_M2, SETTLEMENT_DATE, txns));
    }

    @Test
    @DisplayName("GROSS: non-APPROVED transaction throws IllegalArgumentException")
    void nonApprovedTransaction_throws() {
        TransactionRecord uncertain = new TransactionRecord(
                1L, "TXN-U", "SCH-U", MERCHANT_M2,
                new BigDecimal("50000"), 'G', BigDecimal.ZERO,
                "UNCERTAIN", OffsetDateTime.now(), null);

        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(MERCHANT_M2, SETTLEMENT_DATE, List.of(uncertain)));
    }

    @Test
    @DisplayName("GROSS: FX margin fields do not affect net_settlement_amount (verified by record design)")
    void fxMarginFieldsNotIncluded() {
        // The TransactionRecord does not expose collection_margin_usd in its grossTxnAmount path —
        // the calculator only uses targetPayoutKrw. This test documents and asserts that invariant.
        GrossSettlementSummary summary = calculator.calculate(MERCHANT_M2, SETTLEMENT_DATE,
                List.of(grossTxn("TXN-FX", MERCHANT_M2, "100000")));

        // net == gross regardless of any notional FX margin
        assertEquals(0, summary.netSettlementAmount().compareTo(summary.grossTxnAmount()));
    }

    // -----------------------------------------------------------------------
    // Helper factories
    // -----------------------------------------------------------------------

    private static TransactionRecord grossTxn(String ref, String merchantId, String payout) {
        return new TransactionRecord(
                null, ref, "SCH-" + ref, merchantId,
                new BigDecimal(payout), 'G', BigDecimal.ZERO,
                "APPROVED", OffsetDateTime.now(), null);
    }

    private static TransactionRecord netTxn(String ref, String merchantId,
                                             String payout, String feeRate) {
        return new TransactionRecord(
                null, ref, "SCH-" + ref, merchantId,
                new BigDecimal(payout), 'N', new BigDecimal(feeRate),
                "APPROVED", OffsetDateTime.now(), null);
    }
}
