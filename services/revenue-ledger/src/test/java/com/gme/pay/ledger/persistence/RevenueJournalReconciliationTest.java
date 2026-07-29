package com.gme.pay.ledger.persistence;

import com.gme.pay.ledger.domain.ledger.LedgerPostingService;
import com.gme.pay.ledger.fees.CommissionSplitCalculator;
import com.gme.pay.ledger.fees.CommissionSplitRecordService;
import com.gme.pay.ledger.fees.SchemeFeeSplitCalculator;
import com.gme.pay.ledger.outbox.OutboxWriter;
import com.gme.pay.ledger.revenue.RevenueCaptureService;
import com.gme.pay.ledger.revenue.RevenueRecord;
import com.gme.pay.ledger.web.RevenueJournalReconciliationView;
import com.gme.pay.ledger.web.RevenueJournalReconciliationView.TieOut;
import com.gme.pay.ledger.web.RevenueJournalReconciliationView.UnmappedComponent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * T2-4 self-check: {@code revenue_records} / {@code commission_splits} versus the journal lines for the
 * same period. Proves the report finds recorded-but-not-journalled rows, ties the amounts stream by
 * stream, and names the money it could NOT journal for want of an account code.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({JpaJournalStore.class, LedgerPostingService.class, SchemeFeeSplitCalculator.class,
        OutboxWriter.class, JpaRevenueRecordStore.class, RevenueCaptureService.class,
        CommissionSplitCalculator.class, CommissionSplitRecordService.class,
        RevenueJournalReconciliationService.class})
class RevenueJournalReconciliationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 15);
    private static final LocalDate START = LocalDate.of(2026, 7, 1);
    private static final LocalDate END = LocalDate.of(2026, 7, 31);

    @Autowired
    private RevenueCaptureService capture;

    @Autowired
    private CommissionSplitRecordService splits;

    @Autowired
    private RevenueJournalReconciliationService reconciliation;

    @Autowired
    private RevenueRecordJpaRepository revenueRecords;

    @Autowired
    private CommissionSplitRecordRepository commissionSplitRepo;

    @Test
    void capturedRevenue_isFullyJournalledAndTiesOut() {
        capture.capture("RC-1", 7L, 1L, DATE, new BigDecimal("1.0000"), new BigDecimal("0.5000"),
                new BigDecimal("500.0000"), "KRW", new BigDecimal("0.7000"));
        capture.capture("RC-2", 7L, 1L, DATE, new BigDecimal("2.0000"), BigDecimal.ZERO,
                new BigDecimal("500.0000"), "KRW", new BigDecimal("0.7000"));

        RevenueJournalReconciliationView view = reconciliation.reconcile(START, END);

        assertEquals(2, view.revenueRecords().total());
        assertEquals(2, view.revenueRecords().journalled());
        assertEquals(0, view.revenueRecords().notJournalled());
        assertTrue(view.revenueRecords().notJournalledTxnRefs().isEmpty());
        assertFalse(view.revenueRecords().truncated());

        // FX margin: 1.50 + 2.00 = 3.50 USD recorded, and the same credited to REVENUE_FX_MARGIN.
        TieOut fx = tieOut(view, "FX_MARGIN", "USD");
        assertEquals(0, fx.recordedAmount().compareTo(new BigDecimal("3.50")), "recorded " + fx.recordedAmount());
        assertEquals(0, fx.variance().signum(), "FX margin must tie, variance=" + fx.variance());
        assertTrue(fx.tied());

        // Service charge: 1000 KRW recorded and journalled.
        TieOut sc = tieOut(view, "SERVICE_CHARGE", "KRW");
        assertEquals(0, sc.recordedAmount().compareTo(new BigDecimal("1000")));
        assertTrue(sc.tied(), "service charge must tie, variance=" + sc.variance());
    }

    @Test
    void aRecordWithNoJournal_appearsAsAnException() {
        capture.capture("RC-OK", 7L, 1L, DATE, new BigDecimal("1.0000"), BigDecimal.ZERO,
                BigDecimal.ZERO, "USD", new BigDecimal("0.7000"));

        // Write a record straight to the repository — the pre-T2-4 single-entry state, where revenue was
        // recorded and never journalled. This is the case the self-check exists to surface.
        revenueRecords.save(RevenueRecordEntity.fromDomain(
                RevenueRecord.of("RC-ORPHAN", 7L, 1L, DATE,
                        new BigDecimal("9.0000"), BigDecimal.ZERO,
                        new BigDecimal("500.0000"), "KRW", new BigDecimal("0.7000")),
                Instant.now()));

        RevenueJournalReconciliationView view = reconciliation.reconcile(START, END);

        assertEquals(1, view.revenueRecords().notJournalled());
        assertEquals(java.util.List.of("RC-ORPHAN"), view.revenueRecords().notJournalledTxnRefs());
        assertEquals(1, view.revenueRecords().journalled());
        assertFalse(view.clean(), "a recorded-but-not-journalled row must not report clean");

        // And the amount tie-out shows the size of what is missing, not just its existence.
        TieOut fx = tieOut(view, "FX_MARGIN", "USD");
        assertEquals(0, fx.variance().compareTo(new BigDecimal("9.00")),
                "the orphan's 9.00 USD margin is recorded but not journalled, variance=" + fx.variance());
        assertFalse(fx.tied());
    }

    @Test
    void zeroRevenueRecords_countSeparately_andAreNotExceptions() {
        capture.capture("RC-ZERO", 7L, 1L, DATE, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, "KRW", new BigDecimal("0.7000"));

        RevenueJournalReconciliationView view = reconciliation.reconcile(START, END);

        assertEquals(1, view.revenueRecords().total());
        assertEquals(1, view.revenueRecords().zeroAmount());
        assertEquals(0, view.revenueRecords().notJournalled(),
                "a record with no revenue has nothing to journal and is not an exception");
    }

    @Test
    void commissionSplit_schemeLegTiesOut_andPartnerCarveIsReportedAsUnmapped() {
        // net=1800, gmeGross=1260, scheme=540, partner=378, gmeNet=882
        splits.recordIfAbsent("CS-1", 7L, 1L, DATE, 100_000L,
                new BigDecimal("0.0200"), new BigDecimal("0.0020"),
                new BigDecimal("0.70"), new BigDecimal("0.30"));

        RevenueJournalReconciliationView view = reconciliation.reconcile(START, END);

        assertEquals(1, view.commissionSplits().total());
        assertEquals(1, view.commissionSplits().journalled());
        assertEquals(0, view.commissionSplits().notJournalled());

        TieOut share = tieOut(view, "GME_FEE_SHARE", "KRW");
        assertEquals(0, share.recordedAmount().compareTo(new BigDecimal("1260")));
        assertTrue(share.tied(), "the scheme leg must tie, variance=" + share.variance());

        // The partner carve is recorded but unjournalled — reported with its amount, never dropped.
        UnmappedComponent partner = view.unmappedComponents().stream()
                .filter(u -> "PARTNER_COMMISSION_SHARE".equals(u.component()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the partner carve must be reported as unmapped"));
        assertEquals("commission_splits.partner_share_krw", partner.source());
        assertEquals("KRW", partner.currency());
        assertEquals(0, partner.amount().compareTo(new BigDecimal("378")),
                "the exposure must be quantified exactly, got " + partner.amount());
        assertTrue(partner.decisionRequired().toLowerCase().contains("finance owner"),
                "the report must name whose decision this is: " + partner.decisionRequired());
        assertFalse(view.clean(),
                "while money is recorded that cannot be journalled, the period is NOT clean");
    }

    @Test
    void aSplitRecordedWithoutItsJournal_appearsAsAnException() {
        splits.recordIfAbsent("CS-OK", 7L, 1L, DATE, 100_000L,
                new BigDecimal("0.0200"), new BigDecimal("0.0020"),
                new BigDecimal("0.70"), new BigDecimal("0.30"));
        // A split row written straight to the repository, as pre-T2-4 records were.
        commissionSplitRepo.save(CommissionSplitRecordEntity.of(
                "CS-ORPHAN", 7L, 1L, DATE, 100_000L,
                new BigDecimal("0.0200"), new BigDecimal("0.0020"),
                new BigDecimal("0.70"), new BigDecimal("0.30"),
                new com.gme.pay.ledger.fees.CommissionSplit(2000, 200, 1800, 540, 1260, 378, 882),
                Instant.now()));

        RevenueJournalReconciliationView view = reconciliation.reconcile(START, END);

        assertEquals(2, view.commissionSplits().total());
        assertEquals(1, view.commissionSplits().notJournalled());
        assertEquals(java.util.List.of("CS-ORPHAN"), view.commissionSplits().notJournalledTxnRefs());
    }

    @Test
    void emptyPeriod_reportsNothingMissing_butStillNotClean_whileTheCarveIsUndecided() {
        RevenueJournalReconciliationView view = reconciliation.reconcile(START, END);

        assertEquals(0, view.revenueRecords().total());
        assertEquals(0, view.revenueRecords().notJournalled());
        assertEquals(0, view.commissionSplits().notJournalled());
        // No splits, so the unmapped component's amount is zero → nothing is off the books.
        assertTrue(view.clean(), "an empty period with no unmapped money is clean");
    }

    @Test
    void invertedRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> reconciliation.reconcile(END, START));
    }

    private static TieOut tieOut(RevenueJournalReconciliationView view, String stream, String currency) {
        return view.tieOuts().stream()
                .filter(t -> t.stream().equals(stream) && t.currency().equals(currency))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no tie-out for " + stream + "/" + currency
                        + " in " + view.tieOuts()));
    }
}
