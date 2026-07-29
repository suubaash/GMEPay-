package com.gme.pay.settlement.corridor;

import com.gme.pay.settlement.alert.ReconBreakAlerter;
import com.gme.pay.settlement.persistence.CorridorReconSummaryEntity;
import com.gme.pay.settlement.persistence.CorridorReconSummaryRepository;
import com.gme.pay.settlement.persistence.ReconExceptionEntity;
import com.gme.pay.settlement.persistence.ReconExceptionRepository;
import com.gme.pay.settlement.port.PrefundingMovementPort;
import com.gme.pay.settlement.port.SchemeSettlementPort;
import com.gme.pay.settlement.port.SchemeTransactionPort;
import com.gme.pay.settlement.recon.MatchStatus;
import com.gme.pay.events.DomainEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The SENDMN three-way tie-out (GAP T2-2).
 *
 * <p>Fixtures use the real corridor arithmetic. A clean payment:
 * ₩100,000 + ₩500 fee = ₩100,500 charged; deducted at a live 1,340 KRW/USD ⇒ $75.00 off the float;
 * 239,440 MNT paid, confirmed by SendMN at its registered 3,280 MNT/USD ⇒ $73.00 owed. Variance
 * +$2.00 = the intended fee + FX margin, so the line MATCHES and the day shows +$2.00 of retained USD.
 *
 * <p>The rate-basis fixture keeps the same ₩100,500 but prices it off the hub's hardcoded 1,350
 * fallback ⇒ $74.44444444 collected, while 255,000 MNT at the registered 3,280 makes $77.7439 owed —
 * a $3.29945556 SHORTFALL that used to accrue with nobody comparing the two bases.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CorridorThreeWayReconcilerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 28);
    private static final String BATCH_ID = "SENDMN-3WAY-20260728";
    private static final Instant AT = Instant.parse("2026-07-28T01:00:00Z");

    @Mock
    private ReconExceptionRepository exceptionRepository;

    @Mock
    private CorridorReconSummaryRepository summaryRepository;

    /** In-memory stand-in for the summary table, so the cumulative recompute is exercised for real. */
    private final Map<LocalDate, CorridorReconSummaryEntity> summaryStore = new TreeMap<>();

    private final List<ReconExceptionEntity> savedExceptions = new ArrayList<>();
    private final List<DomainEvent> alerts = new ArrayList<>();

    private List<SchemeTransactionRecord> txns = List.of();
    private List<PrefundingMovement> movements = List.of();
    private List<SchemeSettlementRecord> schemeRecords = List.of();

    private CorridorThreeWayReconciler reconciler;

    @BeforeEach
    void setUp() {
        when(exceptionRepository.save(any(ReconExceptionEntity.class))).thenAnswer(inv -> {
            ReconExceptionEntity e = inv.getArgument(0);
            savedExceptions.add(e);
            return e;
        });
        when(summaryRepository.findBySettlementDateAndScheme(any(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(summaryStore.get(inv.getArgument(0))));
        when(summaryRepository.save(any(CorridorReconSummaryEntity.class))).thenAnswer(inv -> {
            CorridorReconSummaryEntity e = inv.getArgument(0);
            summaryStore.put(e.getSettlementDate(), e);
            return e;
        });
        when(summaryRepository.findBySchemeOrderBySettlementDateAsc(anyString()))
                .thenAnswer(inv -> new ArrayList<>(summaryStore.values()));

        SchemeTransactionPort transactionPort = (schemeId, date) -> txns;
        PrefundingMovementPort prefundingPort = (partnerCode, date) -> movements;
        SchemeSettlementPort schemePort = new SchemeSettlementPort() {
            @Override
            public String scheme() {
                return "SENDMN";
            }

            @Override
            public List<SchemeSettlementRecord> confirmedOn(LocalDate date) {
                return schemeRecords;
            }
        };

        CorridorSpec spec = new CorridorSpec("SENDMN", "sendmn", "SENDMN", "KRW->MNT", "MNT",
                new BigDecimal("500"), new BigDecimal("1350"), new BigDecimal("0.5"), BigDecimal.ZERO);

        reconciler = new CorridorThreeWayReconciler(spec, transactionPort, prefundingPort, schemePort,
                new PendingFormatReconFeedParser("SENDMN", "O4 — SendMN recon file format"),
                exceptionRepository, summaryRepository, new ReconBreakAlerter(alerts::add));
    }

    // ---------------------------------------------------------------------------------
    // Clean day
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a fully matched day reconciles clean: no breaks, no alert, retained USD on the summary")
    void matchedDayReconcilesClean() {
        givenCleanPayment("SENDMN-a");
        givenCleanPayment("SENDMN-b");

        CorridorReconResult result = reconciler.reconcile(DATE);

        assertThat(result.batchId()).isEqualTo(BATCH_ID);
        assertThat(result.lines()).hasSize(2);
        assertThat(result.lines()).allMatch(l -> l.matchStatus() == MatchStatus.MATCHED);
        assertThat(result.breaks()).isEmpty();
        assertThat(savedExceptions).isEmpty();
        assertThat(alerts).isEmpty();

        CorridorReconSummaryEntity summary = result.summary();
        assertThat(summary.getTxnCount()).isEqualTo(2);
        assertThat(summary.getChargedKrw()).isEqualByComparingTo("201000");     // 2 × ₩100,500
        assertThat(summary.getLocalPaid()).isEqualByComparingTo("478880");      // 2 × 239,440 MNT
        assertThat(summary.getUsdDeducted()).isEqualByComparingTo("150.00");    // 2 × $75.00
        assertThat(summary.getUsdOwedScheme()).isEqualByComparingTo("146.00");  // 2 × $73.00
        assertThat(summary.getRateBasisVarianceUsd()).isEqualByComparingTo("4.00");
        assertThat(summary.getCumulativeVarianceUsd()).isEqualByComparingTo("4.00");
        assertThat(summary.getBreakCount()).isZero();
        assertThat(summary.getFallbackRateBasisCount()).isZero();
        // No partner file exists (SendMN format = external gate O4), and the summary says so.
        assertThat(summary.isSchemeFeedAvailable()).isFalse();
    }

    // ---------------------------------------------------------------------------------
    // Missing on one side
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("our APPROVED transaction with no scheme confirmation raises MISSING_SCHEME")
    void missingSchemeBreak() {
        txns = List.of(txn("SENDMN-a", "100000", "239440", "75.00"));
        movements = List.of(new PrefundingMovement("SENDMN-a", new BigDecimal("75.00"), AT));
        schemeRecords = List.of();

        CorridorReconResult result = reconciler.reconcile(DATE);

        assertThat(result.lines()).singleElement()
                .extracting(ThreeWayLine::matchStatus).isEqualTo(MatchStatus.MISSING_SCHEME);
        assertThat(savedExceptions).singleElement().satisfies(e -> {
            assertThat(e.getBatchId()).isEqualTo(BATCH_ID);
            assertThat(e.getScheme()).isEqualTo("SENDMN");
            assertThat(e.getTxnRef()).isEqualTo("txn-SENDMN-a");
            assertThat(e.getMatchStatus()).isEqualTo(MatchStatus.MISSING_SCHEME);
            assertThat(e.getGmeAmount()).isEqualByComparingTo("75.00");
            assertThat(e.getSchemeAmount()).isNull();
        });
        assertThat(alerts).hasSize(1);   // reuses the existing RECON_BREAK ops alert
        assertThat(result.summary().getBreakCount()).isEqualTo(1);
        assertThat(result.summary().getBreakValueUsd()).isEqualByComparingTo("75.00");
    }

    @Test
    @DisplayName("a scheme-confirmed payment we have no transaction for raises MISSING_INTERNAL")
    void missingInternalBreak() {
        txns = List.of();
        movements = List.of();
        schemeRecords = List.of(schemeRow("SENDMN-orphan", "239440", "73.00"));

        CorridorReconResult result = reconciler.reconcile(DATE);

        assertThat(result.lines()).singleElement()
                .extracting(ThreeWayLine::matchStatus).isEqualTo(MatchStatus.MISSING_INTERNAL);
        assertThat(savedExceptions).singleElement().satisfies(e -> {
            assertThat(e.getMatchStatus()).isEqualTo(MatchStatus.MISSING_INTERNAL);
            assertThat(e.getTxnRef()).isEqualTo("SENDMN-orphan");   // no txnRef → the join reference
            assertThat(e.getSchemeAmount()).isEqualByComparingTo("73.00");
        });
        assertThat(alerts).hasSize(1);
    }

    @Test
    @DisplayName("a paid, scheme-confirmed transaction with no float movement raises MISSING_PREFUNDING")
    void missingPrefundingBreak() {
        txns = List.of(txn("SENDMN-a", "100000", "239440", "75.00"));
        movements = List.of();
        schemeRecords = List.of(schemeRow("SENDMN-a", "239440", "73.00"));

        CorridorReconResult result = reconciler.reconcile(DATE);

        assertThat(result.lines()).singleElement().satisfies(l -> {
            assertThat(l.matchStatus()).isEqualTo(MatchStatus.MISSING_PREFUNDING);
            // A missing leg is never ALSO reported as a variance — there is nothing to subtract.
            assertThat(l.rateBasisVarianceUsd()).isNull();
        });
        assertThat(savedExceptions).singleElement()
                .extracting(ReconExceptionEntity::getMatchStatus).isEqualTo(MatchStatus.MISSING_PREFUNDING);
        assertThat(result.summary().getRateBasisVarianceUsd()).isEqualByComparingTo("0");
    }

    // ---------------------------------------------------------------------------------
    // Amount mismatch
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("prefunding USD differing from the transaction's USD raises DISCREPANCY")
    void usdAmountMismatchBreak() {
        txns = List.of(txn("SENDMN-a", "100000", "239440", "75.00"));
        movements = List.of(new PrefundingMovement("SENDMN-a", new BigDecimal("70.00"), AT));
        schemeRecords = List.of(schemeRow("SENDMN-a", "239440", "73.00"));

        CorridorReconResult result = reconciler.reconcile(DATE);

        assertThat(result.lines()).singleElement().satisfies(l -> {
            assertThat(l.matchStatus()).isEqualTo(MatchStatus.DISCREPANCY);
            assertThat(l.usdDeducted()).isEqualByComparingTo("75.00");
            assertThat(l.usdMovedPrefunding()).isEqualByComparingTo("70.00");
            assertThat(l.note()).contains("differs from the prefunding movement");
        });
        assertThat(savedExceptions).singleElement()
                .extracting(ReconExceptionEntity::getMatchStatus).isEqualTo(MatchStatus.DISCREPANCY);
    }

    @Test
    @DisplayName("MNT paid differing from what the scheme confirmed raises DISCREPANCY")
    void localAmountMismatchBreak() {
        txns = List.of(txn("SENDMN-a", "100000", "239440", "75.00"));
        movements = List.of(new PrefundingMovement("SENDMN-a", new BigDecimal("75.00"), AT));
        schemeRecords = List.of(schemeRow("SENDMN-a", "200000", "60.9756"));

        CorridorReconResult result = reconciler.reconcile(DATE);

        assertThat(result.lines()).singleElement().satisfies(l -> {
            assertThat(l.matchStatus()).isEqualTo(MatchStatus.DISCREPANCY);
            assertThat(l.note()).contains("MNT paid per our transaction");
        });
    }

    // ---------------------------------------------------------------------------------
    // Rate-basis variance — the CFO risk this gap was about
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("USD short of the registered-rate obligation is flagged RATE_BASIS_VARIANCE, "
            + "computed exactly, and rolled into the day + cumulative totals")
    void rateBasisVarianceComputedAndSurfaced() {
        // ₩100,500 priced off the hub's 1350 FALLBACK ⇒ $74.44444444 collected …
        txns = List.of(txn("SENDMN-a", "100000", "255000", "74.44444444"));
        movements = List.of(new PrefundingMovement("SENDMN-a", new BigDecimal("74.44444444"), AT));
        // … while 255,000 MNT at SendMN's registered 3,280 makes $77.7439 owed.
        schemeRecords = List.of(schemeRow("SENDMN-a", "255000", "77.7439"));

        CorridorReconResult result = reconciler.reconcile(DATE);

        ThreeWayLine line = result.lines().get(0);
        assertThat(line.matchStatus()).isEqualTo(MatchStatus.RATE_BASIS_VARIANCE);
        assertThat(line.rateBasisVarianceUsd()).isEqualByComparingTo("-3.29945556");
        assertThat(line.usdOwedScheme()).isEqualByComparingTo("77.7439");
        assertThat(line.registeredRate()).isEqualByComparingTo("3280");
        // The rate basis the hub actually used, back-derived from ₩100,500 / $74.44444444.
        assertThat(line.impliedKrwPerUsd()).isEqualByComparingTo("1350.0000");
        assertThat(line.fallbackRateBasis()).isTrue();
        assertThat(line.note()).contains("short of the USD owed SENDMN").contains("fallback");

        // Surfaced through the same ops queue + alerter as every other break.
        assertThat(savedExceptions).singleElement().satisfies(e -> {
            assertThat(e.getMatchStatus()).isEqualTo(MatchStatus.RATE_BASIS_VARIANCE);
            assertThat(e.getScheme()).isEqualTo("SENDMN");
            assertThat(e.getDiscrepancyAmount()).isEqualByComparingTo("3.29945556");
        });
        assertThat(alerts).hasSize(1);

        CorridorReconSummaryEntity summary = result.summary();
        assertThat(summary.getRateBasisVarianceUsd()).isEqualByComparingTo("-3.29945556");
        assertThat(summary.getCumulativeVarianceUsd()).isEqualByComparingTo("-3.29945556");
        assertThat(summary.getFallbackRateBasisCount()).isEqualTo(1);
        assertThat(summary.getBreakCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("cumulative variance accumulates across days, signed — the invisible drift becomes visible")
    void cumulativeVarianceAccumulatesAcrossDays() {
        // Day 1: a clean day retains +$2.00.
        givenCleanPayment("SENDMN-d1");
        reconciler.reconcile(DATE);

        // Day 2: a shortfall of −$3.29945556.
        savedExceptions.clear();
        txns = List.of(txn("SENDMN-d2", "100000", "255000", "74.44444444"));
        movements = List.of(new PrefundingMovement("SENDMN-d2", new BigDecimal("74.44444444"), AT));
        schemeRecords = List.of(schemeRow("SENDMN-d2", "255000", "77.7439"));
        CorridorReconResult day2 = reconciler.reconcile(DATE.plusDays(1));

        assertThat(day2.summary().getRateBasisVarianceUsd()).isEqualByComparingTo("-3.29945556");
        assertThat(day2.summary().getCumulativeVarianceUsd()).isEqualByComparingTo("-1.29945556");
        assertThat(summaryStore.get(DATE).getCumulativeVarianceUsd()).isEqualByComparingTo("2.00");
    }

    // ---------------------------------------------------------------------------------
    // Idempotency
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("re-running the same date is idempotent: breaks are replaced, the summary is not double-counted")
    void reRunSameDateIsIdempotent() {
        txns = List.of(txn("SENDMN-a", "100000", "239440", "75.00"));
        movements = List.of();
        schemeRecords = List.of(schemeRow("SENDMN-a", "239440", "73.00"));

        CorridorReconResult first = reconciler.reconcile(DATE);
        CorridorReconResult second = reconciler.reconcile(DATE);

        // Prior rows for the run are cleared before each re-insert (same guarantee as the ZeroPay lane).
        verify(exceptionRepository, times(2)).deleteByBatchId(BATCH_ID);
        assertThat(first.batchId()).isEqualTo(second.batchId());
        assertThat(second.summary().getBreakCount()).isEqualTo(1);
        // One summary row for the date, and the cumulative reflects one day, not two runs.
        assertThat(summaryStore).hasSize(1);
        assertThat(second.summary().getCumulativeVarianceUsd())
                .isEqualByComparingTo(second.summary().getRateBasisVarianceUsd());
    }

    // ---------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------

    /** ₩100,000 (+₩500 fee) → 239,440 MNT, $75.00 off the float, $73.00 owed SendMN: a clean match. */
    private void givenCleanPayment(String reference) {
        List<SchemeTransactionRecord> t = new ArrayList<>(txns);
        List<PrefundingMovement> m = new ArrayList<>(movements);
        List<SchemeSettlementRecord> s = new ArrayList<>(schemeRecords);
        t.add(txn(reference, "100000", "239440", "75.00"));
        m.add(new PrefundingMovement(reference, new BigDecimal("75.00"), AT));
        s.add(schemeRow(reference, "239440", "73.00"));
        txns = t;
        movements = m;
        schemeRecords = s;
    }

    private static SchemeTransactionRecord txn(String reference, String krw, String mnt, String usd) {
        return new SchemeTransactionRecord(
                "txn-" + reference, reference, "merchant-1", "sendmn",
                "KRW", new BigDecimal(krw), "MNT", new BigDecimal(mnt),
                new BigDecimal(usd), "APPROVED", AT);
    }

    private static SchemeSettlementRecord schemeRow(String reference, String mnt, String usd) {
        return new SchemeSettlementRecord(
                reference, "SMN-TOK-" + reference, "merchant-1", "MNT", new BigDecimal(mnt),
                new BigDecimal("3280"), "USD", new BigDecimal(usd), "APPROVED", AT);
    }
}
