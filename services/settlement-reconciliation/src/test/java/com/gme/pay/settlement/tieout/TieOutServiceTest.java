package com.gme.pay.settlement.tieout;

import com.gme.pay.settlement.model.TransactionRecord;
import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.persistence.SettlementBatchRepository;
import com.gme.pay.settlement.persistence.SettlementLineEntity;
import com.gme.pay.settlement.persistence.SettlementLineRepository;
import com.gme.pay.settlement.port.LedgerRevenuePort;
import com.gme.pay.settlement.port.TransactionQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TieOutService} — the daily cent-for-cent tie-out over mocked ports/repos
 * (no Spring context).
 *
 * <p>Baseline day: one batch with two settled payment lines (+60000, +40000 → settled gross
 * 100000), one refund clawback line (−1000), header net 97000 / fee 2000 (persisted at scale 4 to
 * prove value-equality, not scale-equality), plus one still-unbatched approved txn of 5000. So
 * gross = net + fee + refund holds (97000 + 2000 + 1000 == 100000) and txnGross = 5000 + 100000.
 */
@ExtendWith(MockitoExtension.class)
class TieOutServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 15);
    private static final String BATCH_ID = "ZP0061-20260615-MORNING";

    @Mock private TransactionQueryPort transactionQueryPort;
    @Mock private SettlementBatchRepository batchRepository;
    @Mock private SettlementLineRepository lineRepository;
    @Mock private LedgerRevenuePort ledgerRevenuePort;

    private TieOutService service;

    @BeforeEach
    void setUp() {
        service = new TieOutService(
                transactionQueryPort, batchRepository, lineRepository, ledgerRevenuePort);
    }

    @Test
    @DisplayName("balanced day with agreeing ledger ties out: delta 0, txnGross = unbatched + settled lines")
    void balancedDayTiesOut() {
        stubBalancedDay();
        when(ledgerRevenuePort.revenueFor(DATE)).thenReturn(new BigDecimal("2000"));

        TieOutReport report = service.report(DATE);

        assertThat(report.businessDate()).isEqualTo(DATE);
        assertThat(report.txnGrossKrw()).isEqualByComparingTo("105000");
        assertThat(report.settledNetKrw()).isEqualByComparingTo("97000");
        assertThat(report.settledFeeKrw()).isEqualByComparingTo("2000");
        assertThat(report.settledRefundKrw()).isEqualByComparingTo("1000");
        assertThat(report.settlementBalanced()).isTrue();
        assertThat(report.ledgerAvailable()).isTrue();
        assertThat(report.ledgerFeeKrw()).isEqualByComparingTo("2000");
        assertThat(report.ledgerDeltaKrw()).isEqualByComparingTo("0");
        assertThat(report.tiedOut()).isTrue();
    }

    @Test
    @DisplayName("ledger disagrees (delta != 0) → tiedOut=false even though settlement is balanced")
    void ledgerDeltaBreaksTieOut() {
        stubBalancedDay();
        when(ledgerRevenuePort.revenueFor(DATE)).thenReturn(new BigDecimal("1500"));

        TieOutReport report = service.report(DATE);

        assertThat(report.settlementBalanced()).isTrue();
        assertThat(report.ledgerAvailable()).isTrue();
        assertThat(report.ledgerDeltaKrw()).isEqualByComparingTo("500");   // settledFee 2000 − ledger 1500
        assertThat(report.tiedOut()).isFalse();
    }

    @Test
    @DisplayName("ledger unavailable (null) → ledgerAvailable=false, delta omitted, tiedOut driven by balance alone")
    void ledgerUnavailableFallsBackToBalance() {
        stubBalancedDay();
        when(ledgerRevenuePort.revenueFor(DATE)).thenReturn(null);

        TieOutReport report = service.report(DATE);

        assertThat(report.settlementBalanced()).isTrue();
        assertThat(report.ledgerAvailable()).isFalse();
        assertThat(report.ledgerFeeKrw()).isNull();
        assertThat(report.ledgerDeltaKrw()).isNull();
        assertThat(report.tiedOut()).isTrue();   // balanced day ties out without a ledger figure
    }

    @Test
    @DisplayName("unbalanced batch header (net+fee+refund != settled gross) → tiedOut=false even without ledger")
    void unbalancedDayNeverTiesOut() {
        SettlementBatchEntity batch = batch("96000", "2000");   // 96000+2000+1000 = 99000 != 100000
        when(transactionQueryPort.findUnbatchedApproved(DATE)).thenReturn(List.of());
        when(batchRepository.findByBusinessDate(DATE)).thenReturn(List.of(batch));
        when(lineRepository.findByBatchId(BATCH_ID)).thenReturn(lines());
        when(ledgerRevenuePort.revenueFor(DATE)).thenReturn(null);

        TieOutReport report = service.report(DATE);

        assertThat(report.settlementBalanced()).isFalse();
        assertThat(report.ledgerAvailable()).isFalse();
        assertThat(report.tiedOut()).isFalse();
    }

    // ------------------------------------------------------------------ fixtures

    /** One balanced batch + one unbatched approved txn of 5000. Never stubs inside when(...) calls. */
    private void stubBalancedDay() {
        List<TransactionRecord> unbatched = List.of(approvedTxn("TXN-U1", "5000"));
        SettlementBatchEntity batch = batch("97000.0000", "2000.0000");   // header scale 4 on purpose
        List<SettlementLineEntity> batchLines = lines();

        when(transactionQueryPort.findUnbatchedApproved(DATE)).thenReturn(unbatched);
        when(batchRepository.findByBusinessDate(DATE)).thenReturn(List.of(batch));
        when(lineRepository.findByBatchId(BATCH_ID)).thenReturn(batchLines);
    }

    /** Two settled payments (+60000, +40000) and one refund clawback (−1000), line scale 8. */
    private static List<SettlementLineEntity> lines() {
        return List.of(
                line("TXN-1", "60000.00000000"),
                line("TXN-2", "40000.00000000"),
                line("TXN-3", "-1000.00000000"));
    }

    private static SettlementBatchEntity batch(String net, String fee) {
        SettlementBatchEntity batch = new SettlementBatchEntity(
                BATCH_ID, "ZEROPAY", DATE, "GENERATED",
                new BigDecimal(net), "KRW", Instant.parse("2026-06-15T05:00:00Z"));
        batch.setNetSettlementAmount(new BigDecimal(net));
        batch.setMerchantFeeTotal(new BigDecimal(fee));
        return batch;
    }

    private static SettlementLineEntity line(String txnRef, String amount) {
        return new SettlementLineEntity(BATCH_ID, txnRef, new BigDecimal(amount), "KRW", false);
    }

    private static TransactionRecord approvedTxn(String txnRef, String payoutKrw) {
        return new TransactionRecord(
                1L, txnRef, "SCHEME-" + txnRef, "MERCHANT-1",
                new BigDecimal(payoutKrw), 'N', new BigDecimal("0.008"),
                "APPROVED", null, null);
    }
}
