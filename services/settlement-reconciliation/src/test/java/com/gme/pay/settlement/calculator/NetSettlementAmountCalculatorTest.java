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
 * Plain JUnit 5 unit tests for {@link NetSettlementAmountCalculator}.
 * No Spring context, no Docker, no Testcontainers.
 *
 * Spec reference: 7.1-T05 acceptance checks.
 */
class NetSettlementAmountCalculatorTest {

    private NetSettlementAmountCalculator calculator;
    private static final LocalDate SETTLEMENT_DATE = LocalDate.of(2026, 6, 8);
    private static final String MERCHANT_M1 = "M1";

    @BeforeEach
    void setUp() {
        calculator = new NetSettlementAmountCalculator();
    }

    // -----------------------------------------------------------------------
    // Spec check: given 2 transactions for merchant M1 with target_payout
    // 15000 KRW and 20000 KRW, fee_rate=0.008 (0.8%):
    //   gross_txn_amount=35000
    //   merchant_fee_total = ROUND(15000*0.008,0) + ROUND(20000*0.008,0) = 120 + 160 = 280
    //   net_settlement_amount = 35000 - 280 = 34720
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("NET: two transactions, fee_rate=0.8% -> net=34720")
    void twoTransactions_netCorrect() {
        List<TransactionRecord> txns = List.of(
                netTxn("TXN-001", MERCHANT_M1, "15000", "0.008"),
                netTxn("TXN-002", MERCHANT_M1, "20000", "0.008")
        );

        NetSettlementSummary summary = calculator.calculate(MERCHANT_M1, SETTLEMENT_DATE, txns);

        assertEquals(MERCHANT_M1, summary.merchantId());
        assertEquals(2, summary.grossTxnCount());
        assertEquals(new BigDecimal("35000"), summary.grossTxnAmount());
        assertEquals(new BigDecimal("280"), summary.merchantFeeTotal());
        assertEquals(new BigDecimal("34720"), summary.netSettlementAmount());
        assertEquals('N', summary.settlementType());
    }

    @Test
    @DisplayName("NET: empty transaction list returns zero summary without error")
    void emptyTransactions_returnsZeroSummary() {
        NetSettlementSummary summary = calculator.calculate(MERCHANT_M1, SETTLEMENT_DATE, List.of());

        assertEquals(0, summary.grossTxnCount());
        assertEquals(BigDecimal.ZERO, summary.grossTxnAmount());
        assertEquals(BigDecimal.ZERO, summary.merchantFeeTotal());
        assertEquals(BigDecimal.ZERO, summary.netSettlementAmount());
    }

    @Test
    @DisplayName("NET: null transaction list returns zero summary without error")
    void nullTransactions_returnsZeroSummary() {
        NetSettlementSummary summary = calculator.calculate(MERCHANT_M1, SETTLEMENT_DATE, null);

        assertEquals(0, summary.grossTxnCount());
        assertEquals(BigDecimal.ZERO, summary.netSettlementAmount());
    }

    @Test
    @DisplayName("NET: GROSS transaction in input throws IllegalArgumentException")
    void grossTransactionInInput_throws() {
        List<TransactionRecord> txns = List.of(
                grossTxn("TXN-GROSS", MERCHANT_M1, "50000")
        );

        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(MERCHANT_M1, SETTLEMENT_DATE, txns));
    }

    @Test
    @DisplayName("NET: non-APPROVED transaction throws IllegalArgumentException")
    void nonApprovedTransaction_throws() {
        TransactionRecord pending = new TransactionRecord(
                1L, "TXN-P", "SCH-P", MERCHANT_M1,
                new BigDecimal("10000"), 'N', new BigDecimal("0.008"),
                "PENDING", OffsetDateTime.now(), null);

        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(MERCHANT_M1, SETTLEMENT_DATE, List.of(pending)));
    }

    @Test
    @DisplayName("NET: KRW fee rounds HALF_UP at per-transaction level")
    void feeRounding_halfUp() {
        // target_payout=1 KRW, fee_rate=0.008 -> 0.008 rounds to 0 KRW (HALF_UP)
        // target_payout=63 KRW, fee_rate=0.008 -> 0.504 rounds to 1 KRW (HALF_UP)
        List<TransactionRecord> txns = List.of(
                netTxn("TXN-R1", MERCHANT_M1, "1", "0.008"),    // 0.008 -> 0
                netTxn("TXN-R2", MERCHANT_M1, "63", "0.008")    // 0.504 -> 1
        );

        NetSettlementSummary summary = calculator.calculate(MERCHANT_M1, SETTLEMENT_DATE, txns);

        assertEquals(new BigDecimal("64"), summary.grossTxnAmount());
        assertEquals(new BigDecimal("1"), summary.merchantFeeTotal()); // 0 + 1
        assertEquals(new BigDecimal("63"), summary.netSettlementAmount());
    }

    // -----------------------------------------------------------------------
    // Helper factories
    // -----------------------------------------------------------------------

    private static TransactionRecord netTxn(String ref, String merchantId,
                                             String payout, String feeRate) {
        return new TransactionRecord(
                null, ref, "SCH-" + ref, merchantId,
                new BigDecimal(payout), 'N', new BigDecimal(feeRate),
                "APPROVED", OffsetDateTime.now(), null);
    }

    private static TransactionRecord grossTxn(String ref, String merchantId, String payout) {
        return new TransactionRecord(
                null, ref, "SCH-" + ref, merchantId,
                new BigDecimal(payout), 'G', BigDecimal.ZERO,
                "APPROVED", OffsetDateTime.now(), null);
    }
}
