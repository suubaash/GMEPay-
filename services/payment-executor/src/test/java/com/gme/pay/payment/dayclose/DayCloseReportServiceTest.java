package com.gme.pay.payment.dayclose;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.payment.domain.client.PrefundingClient;
import com.gme.pay.payment.domain.client.RevenueLedgerReportClient;
import com.gme.pay.payment.domain.client.TransactionClient;
import com.gme.pay.payment.replay.RevenuePostingOutstandingQuery;
import com.gme.pay.payment.replay.RevenuePostingOutstandingView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>T2-5 / CFO#10 proof</b> for the three-way day-close: "nothing compares prefunding ledger movement vs
 * revenue-ledger postings vs transaction-mgmt for a business day; no day-close artifact finance can sign".
 *
 * <p>The fixture is a known day with a <b>deliberate variance</b> — one partner's float gives up 5 USD less than
 * its transactions claim — plus two open decisions carrying real money. Every assertion is about the report
 * NAMING what it found rather than resolving it:
 *
 * <ul>
 *   <li>{@link #reproducesTheFixtureAndNamesTheDeliberateVariance()} — the corridor/currency arithmetic is exact
 *       and the injected 5 USD gap surfaces as a {@code DEFECT_TO_INVESTIGATE} with both sides labelled;</li>
 *   <li>{@link #openDecisionsAreReportedAsUNRESOLVEDAndNeverNettedAway()} — T2-10 and T2-11 appear as
 *       {@code UNRESOLVED_DECISION} variances AND as {@code unresolvedDecisions} entries with the register item
 *       and the actual question, and they hold {@code clean} false. A report that netted them would look tidy
 *       and be wrong;</li>
 *   <li>{@link #anUnreadableLegIsUNAVAILABLEAndDrawsNoConclusions()} and
 *       {@link #anUnconfiguredPrefundingLegSaysSoRatherThanReportingNoMovement()} — absence is never zero, and a
 *       leg that could not be read raises no variances from the figures it does not have;</li>
 *   <li>{@link #refundSliceMovementsJoinBackToTheirTransaction()} — the refund path's
 *       {@code <txnRef>#REFUND-RETAINED@...} float slices join to their transaction instead of manufacturing a
 *       variance;</li>
 *   <li>{@link #theReplayBacklogIsOnTheReport()} — the one-sided failure a ledger-only report cannot see.</li>
 * </ul>
 */
class DayCloseReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-29T02:00:00Z");
    private static final LocalDate D = LocalDate.of(2026, 7, 28);

    // ---- stubs ----

    /** Transactions for the day, or a throw. */
    private static TransactionClient txnClient(List<TransactionClient.DailyTransaction> rows,
                                               boolean fail) {
        return new TransactionClient() {
            @Override
            public CreateResult createPending(CreateRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void commitStatus(String txnRef, StatusPatch patch) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<DailyTransaction> findApprovedForDate(LocalDate businessDate) {
                if (fail) {
                    throw new IllegalStateException("transaction-mgmt unreachable");
                }
                return rows;
            }
        };
    }

    /** Float movements per partner code, or a throw. */
    private static PrefundingClient prefundingClient(
            Map<String, List<PrefundingClient.FloatMovement>> byCode, boolean fail) {
        return new PrefundingClient() {
            @Override
            public DeductionResult deduct(long partnerId, String txnRef, BigDecimal amountUsd) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ReverseResult reverse(long partnerId, String txnRef) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<FloatMovement> movements(String partnerCode, Instant from, Instant to) {
                if (fail) {
                    throw new IllegalStateException("prefunding unreachable");
                }
                return byCode.getOrDefault(partnerCode, List.of());
            }
        };
    }

    private static RevenueLedgerReportClient ledgerClient(RevenueLedgerReportClient.TrialBalance tb,
                                                         RevenueLedgerReportClient.JournalReconciliation jr) {
        return new RevenueLedgerReportClient() {
            @Override
            public TrialBalance fetchTrialBalance(LocalDate start, LocalDate end) {
                if (tb == null) {
                    throw new IllegalStateException("revenue-ledger unreachable");
                }
                return tb;
            }

            @Override
            public JournalReconciliation fetchJournalReconciliation(LocalDate start, LocalDate end) {
                if (jr == null) {
                    throw new IllegalStateException("revenue-ledger unreachable");
                }
                return jr;
            }
        };
    }

    private static RevenuePostingOutstandingQuery backlog(long pending, long poison, long replayed) {
        return new RevenuePostingOutstandingQuery(null) {
            @Override
            public RevenuePostingOutstandingView outstanding() {
                return new RevenuePostingOutstandingView(pending + poison, pending, poison, 0, replayed,
                        pending + poison > 0 ? NOW.minusSeconds(86_400) : null, List.of());
            }
        };
    }

    /** A clean ledger: balanced, nothing unmapped, everything tied. */
    private static RevenueLedgerReportClient.TrialBalance balancedLedger() {
        return new RevenueLedgerReportClient.TrialBalance(true, List.of(
                new RevenueLedgerReportClient.CurrencyTotals("USD", new BigDecimal("12.34"),
                        new BigDecimal("12.34"), BigDecimal.ZERO, true, 4)),
                List.of(), List.of());
    }

    private static RevenueLedgerReportClient.JournalReconciliation cleanRecon() {
        RevenueLedgerReportClient.Coverage coverage =
                new RevenueLedgerReportClient.Coverage("revenue_records", 3, 3, 0, 0, List.of(), false);
        return new RevenueLedgerReportClient.JournalReconciliation(true, coverage,
                new RevenueLedgerReportClient.Coverage("commission_splits", 0, 0, 0, 0, List.of(), false),
                List.of(), List.of());
    }

    private static TransactionClient.DailyTransaction txn(String ref, String scheme, String collCcy,
                                                         String collAmt, String payCcy, String payAmt,
                                                         String usd) {
        return new TransactionClient.DailyTransaction(ref, scheme, collCcy, new BigDecimal(collAmt), payCcy,
                new BigDecimal(payAmt), usd == null ? null : new BigDecimal(usd), null, null, NOW);
    }

    private static PrefundingClient.FloatMovement movement(String ref, String amountUsd) {
        return new PrefundingClient.FloatMovement(ref, "DEBIT", new BigDecimal(amountUsd), NOW);
    }

    private DayCloseReportService service(TransactionClient txns, RevenueLedgerReportClient ledger,
                                          PrefundingClient prefunding,
                                          RevenuePostingOutstandingQuery backlogQuery,
                                          String partnerCodes) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        FxExposureService fx = new FxExposureService(txns, null, clock, 7, "KRW",
                new BigDecimal("1350"), BigDecimal.ONE);
        DayCloseReportStore store = new DayCloseReportStore(null, new ObjectMapper());
        return new DayCloseReportService(txns, ledger, prefunding, backlogQuery, fx, store, null, clock,
                "Asia/Seoul", partnerCodes);
    }

    private static DayCloseReport.Variance variance(DayCloseReport report, String code) {
        return report.variances().stream()
                .filter(v -> code.equals(v.code()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + code + " variance; got "
                        + report.variances().stream().map(DayCloseReport.Variance::code).toList()));
    }

    private static DayCloseReport.Leg leg(DayCloseReport report, String name) {
        return report.legs().stream().filter(l -> name.equals(l.leg())).findFirst().orElseThrow();
    }

    // ---- tests ----

    @Test
    @DisplayName("reproduces the fixture exactly and NAMES the deliberate 5 USD float variance")
    void reproducesTheFixtureAndNamesTheDeliberateVariance() {
        List<TransactionClient.DailyTransaction> txns = List.of(
                txn("T1", "sendmn", "KRW", "1350000", "MNT", "3000000", "1000"),
                txn("T2", "sendmn", "KRW", "675000", "MNT", "1500000", "500"),
                txn("T3", "zeropay_kr", "KRW", "50000", "KRW", "50000", "37"));
        // SENDMN's float gives up 1495 USD where the transactions claim 1500 — a deliberate 5 USD gap.
        Map<String, List<PrefundingClient.FloatMovement>> movements = Map.of(
                "SENDMN", List.of(movement("T1", "-1000"), movement("T2", "-495")),
                "GMEREMIT", List.of(movement("T3", "-37")));

        DayCloseReport report = service(txnClient(txns, false),
                ledgerClient(balancedLedger(), cleanRecon()),
                prefundingClient(movements, false), backlog(0, 0, 0),
                "SENDMN,GMEREMIT").build(D);

        // -- legs --
        assertThat(report.legs()).hasSize(3);
        assertThat(report.legs()).allMatch(DayCloseReport.Leg::available);

        // -- corridors: derived from the transactions, so a new corridor needs no registration --
        assertThat(report.corridors()).hasSize(2);
        DayCloseReport.CorridorSummary sendmn = report.corridors().stream()
                .filter(c -> "sendmn".equals(c.schemeId())).findFirst().orElseThrow();
        assertThat(sendmn.corridor()).isEqualTo("sendmn KRW->MNT");
        assertThat(sendmn.txnCount()).isEqualTo(2);
        assertThat(sendmn.collectedAmount()).isEqualByComparingTo("2025000");
        assertThat(sendmn.payoutAmount()).isEqualByComparingTo("4500000");
        assertThat(sendmn.prefundDeductedUsd()).isEqualByComparingTo("1500");
        assertThat(sendmn.missingPrefundUsdCount()).isZero();

        // -- currencies: transactions beside the journal's own totals, quoted --
        DayCloseReport.CurrencySummary krw = report.currencies().stream()
                .filter(c -> "KRW".equals(c.currency())).findFirst().orElseThrow();
        assertThat(krw.collectedAmount()).isEqualByComparingTo("2075000");
        assertThat(krw.collectedTxnCount()).isEqualTo(3);
        assertThat(krw.paidOutAmount()).isEqualByComparingTo("50000");
        DayCloseReport.CurrencySummary usd = report.currencies().stream()
                .filter(c -> "USD".equals(c.currency())).findFirst().orElseThrow();
        assertThat(usd.journalDebitTotal())
                .as("the journal's own figure, not a recomputation of it")
                .isEqualByComparingTo("12.34");
        assertThat(usd.journalBalanced()).isTrue();

        // -- the deliberate variance --
        DayCloseReport.Variance v = variance(report, DayCloseReportService.VAR_FLOAT_VS_TRANSACTION_USD);
        assertThat(v.dimension()).isEqualTo("partner=SENDMN");
        assertThat(v.currency()).isEqualTo("USD");
        assertThat(v.leftAmount()).isEqualByComparingTo("1500");
        assertThat(v.rightAmount()).isEqualByComparingTo("1495");
        assertThat(v.delta()).isEqualByComparingTo("5");
        assertThat(v.treatment()).isEqualTo(DayCloseReport.Variance.Treatment.DEFECT_TO_INVESTIGATE);
        assertThat(v.leftLabel()).isNotBlank();
        assertThat(v.rightLabel()).isNotBlank();

        // GMEREMIT ties exactly, so it gets a summary row but no variance.
        assertThat(report.prefunding()).hasSize(2);
        assertThat(report.prefunding().stream()
                .filter(p -> "GMEREMIT".equals(p.partnerCode())).findFirst().orElseThrow().delta())
                .isEqualByComparingTo("0");
        assertThat(report.variances()).hasSize(1);

        assertThat(report.clean()).as("one variance means the day is not closed clean").isFalse();
        assertThat(report.businessDate()).isEqualTo(D);
        assertThat(report.closeZone()).isEqualTo("Asia/Seoul");
        assertThat(report.fxExposure().available())
                .as("the persisted artifact carries the day's FX position")
                .isTrue();
    }

    @Test
    @DisplayName("a day with no variances and all legs readable closes CLEAN")
    void aTidyDayClosesClean() {
        DayCloseReport report = service(
                txnClient(List.of(txn("T1", "sendmn", "KRW", "1350000", "MNT", "3000000", "1000")), false),
                ledgerClient(balancedLedger(), cleanRecon()),
                prefundingClient(Map.of("SENDMN", List.of(movement("T1", "-1000"))), false),
                backlog(0, 0, 5), "SENDMN").build(D);

        assertThat(report.variances()).isEmpty();
        assertThat(report.unresolvedDecisions()).isEmpty();
        assertThat(report.clean()).isTrue();
    }

    @Test
    @DisplayName("T2-10 and T2-11 are reported as UNRESOLVED DECISIONS with their amounts, never netted away")
    void openDecisionsAreReportedAsUNRESOLVEDAndNeverNettedAway() {
        // The ledger reports exactly what T2-4 and T2-6 left open: an unmapped partner-commission carve, and
        // revenue reversed on the date whose booking treatment is undecided.
        RevenueLedgerReportClient.TrialBalance tb = new RevenueLedgerReportClient.TrialBalance(true,
                List.of(new RevenueLedgerReportClient.CurrencyTotals("KRW", new BigDecimal("1000"),
                        new BigDecimal("1000"), BigDecimal.ZERO, true, 6)),
                List.of(),
                List.of(new RevenueLedgerReportClient.AccountRow("REVENUE_REVERSAL", "KRW",
                                new BigDecimal("400"), BigDecimal.ZERO, new BigDecimal("400"), 1),
                        new RevenueLedgerReportClient.AccountRow("RECEIVABLE_PARTNER", "KRW",
                                new BigDecimal("600"), new BigDecimal("1000"), new BigDecimal("-400"), 3)));
        RevenueLedgerReportClient.JournalReconciliation jr =
                new RevenueLedgerReportClient.JournalReconciliation(
                        false,
                        new RevenueLedgerReportClient.Coverage("revenue_records", 2, 2, 0, 0, List.of(), false),
                        new RevenueLedgerReportClient.Coverage("commission_splits", 1, 1, 0, 0, List.of(),
                                false),
                        List.of(),
                        List.of(new RevenueLedgerReportClient.UnmappedComponent("PARTNER_COMMISSION_SHARE",
                                "commission_splits.partner_share_krw", "KRW", new BigDecimal("378"), 1,
                                "no account code exists for the partner-side carve",
                                "which account the carve debits/credits, and whether its counterpart is "
                                        + "commission expense or contra-revenue")));

        DayCloseReport report = service(
                txnClient(List.of(txn("T1", "zeropay_kr", "KRW", "50000", "KRW", "50000", "37")), false),
                ledgerClient(tb, jr),
                prefundingClient(Map.of("GMEREMIT", List.of(movement("T1", "-37"))), false),
                backlog(0, 0, 0), "GMEREMIT").build(D);

        // Both appear as variances, marked as decisions rather than defects.
        DayCloseReport.Variance unmapped = variance(report, DayCloseReportService.VAR_UNMAPPED_COMPONENT);
        assertThat(unmapped.treatment())
                .isEqualTo(DayCloseReport.Variance.Treatment.UNRESOLVED_DECISION);
        assertThat(unmapped.delta())
                .as("the amount at stake is stated, not absorbed")
                .isEqualByComparingTo("378");
        assertThat(unmapped.currency()).isEqualTo("KRW");

        DayCloseReport.Variance reversal =
                variance(report, DayCloseReportService.VAR_REFUND_REVERSAL_TREATMENT);
        assertThat(reversal.treatment())
                .isEqualTo(DayCloseReport.Variance.Treatment.UNRESOLVED_DECISION);
        assertThat(reversal.delta()).isEqualByComparingTo("400");
        assertThat(reversal.note())
                .as("the measurable shape of T2-11(b): the receivable is relieved twice per reversal")
                .contains("RECEIVABLE_PARTNER credits exceed debits by 400");

        // ...and again in the decisions list, with the register item and the real question.
        assertThat(report.unresolvedDecisions()).hasSize(2);
        assertThat(report.unresolvedDecisions())
                .extracting(DayCloseReport.UnresolvedDecision::registerItem)
                .containsExactlyInAnyOrder("T2-10", "T2-11");
        DayCloseReport.UnresolvedDecision t2_10 = report.unresolvedDecisions().stream()
                .filter(d -> "T2-10".equals(d.registerItem())).findFirst().orElseThrow();
        assertThat(t2_10.amount()).isEqualByComparingTo("378");
        assertThat(t2_10.question()).contains("contra-revenue");
        assertThat(t2_10.effectIfUndecided()).contains("NOT on the double-entry books");

        // The ledger's own verdict is carried through, not overridden.
        assertThat(report.ledger().journalReconciliationClean()).isFalse();
        assertThat(report.ledger().trialBalanceBalanced())
                .as("a period can balance perfectly and still not be clean")
                .isTrue();
        assertThat(report.clean())
                .as("an undecided treatment carrying money is not a closed day")
                .isFalse();
    }

    @Test
    @DisplayName("an unreadable leg is UNAVAILABLE, is not zero, and draws no conclusions from figures it lacks")
    void anUnreadableLegIsUNAVAILABLEAndDrawsNoConclusions() {
        DayCloseReport report = service(txnClient(List.of(), true),
                ledgerClient(null, null),
                prefundingClient(Map.of(), false), backlog(0, 0, 0), "SENDMN").build(D);

        DayCloseReport.Leg txnLeg = leg(report, DayCloseReport.LEG_TRANSACTIONS);
        assertThat(txnLeg.available()).isFalse();
        assertThat(txnLeg.reason()).contains("transaction-mgmt unreachable");
        assertThat(leg(report, DayCloseReport.LEG_LEDGER_JOURNAL).available()).isFalse();

        assertThat(report.corridors()).isEmpty();
        assertThat(report.ledger().trialBalanceBalanced())
                .as("an unread ledger must never answer 'balanced'")
                .isFalse();
        assertThat(report.variances())
                .as("with no transactions read, there is nothing to tie the float out against")
                .isEmpty();
        assertThat(report.clean()).isFalse();
    }

    @Test
    @DisplayName("an unconfigured prefunding leg says WHY rather than reporting zero float movement")
    void anUnconfiguredPrefundingLegSaysSoRatherThanReportingNoMovement() {
        DayCloseReport report = service(
                txnClient(List.of(txn("T1", "sendmn", "KRW", "1350000", "MNT", "3000000", "1000")), false),
                ledgerClient(balancedLedger(), cleanRecon()),
                prefundingClient(Map.of(), false), backlog(0, 0, 0), "").build(D);

        DayCloseReport.Leg prefundingLeg = leg(report, DayCloseReport.LEG_PREFUNDING);
        assertThat(prefundingLeg.available()).isFalse();
        assertThat(prefundingLeg.reason())
                .contains("no partner codes are configured")
                .contains("DEPLOYMENT gap");
        assertThat(report.prefunding()).isEmpty();
        assertThat(report.variances())
                .as("a leg that could not be read raises no variance from the numbers it does not have")
                .isEmpty();
        assertThat(report.clean()).isFalse();
    }

    @Test
    @DisplayName("a partially-read prefunding leg is UNAVAILABLE, so no tie-out is concluded from half a day")
    void aPartiallyReadPrefundingLegIsUnavailable() {
        DayCloseReport report = service(
                txnClient(List.of(txn("T1", "sendmn", "KRW", "1350000", "MNT", "3000000", "1000")), false),
                ledgerClient(balancedLedger(), cleanRecon()),
                prefundingClient(Map.of(), true), backlog(0, 0, 0), "SENDMN").build(D);

        assertThat(leg(report, DayCloseReport.LEG_PREFUNDING).available()).isFalse();
        assertThat(leg(report, DayCloseReport.LEG_PREFUNDING).reason()).contains("prefunding unreachable");
        assertThat(report.variances()).isEmpty();
    }

    @Test
    @DisplayName("refund float slices (<txnRef>#REFUND-RETAINED@...) join back to their transaction")
    void refundSliceMovementsJoinBackToTheirTransaction() {
        // T2-6 keys the retained slice of a partial refund as <txnRef>#REFUND-RETAINED@<cumulative>. An exact
        // reference match would leave every refund slice unjoined and manufacture a variance out of a correct day.
        List<PrefundingClient.FloatMovement> movements = new ArrayList<>();
        movements.add(movement("T1", "-1000"));
        movements.add(movement("T1", "1000"));                        // full reverse
        movements.add(movement("T1#REFUND-RETAINED@200", "-800"));    // retained slice

        DayCloseReport report = service(
                txnClient(List.of(txn("T1", "sendmn", "KRW", "1080000", "MNT", "2400000", "800")), false),
                ledgerClient(balancedLedger(), cleanRecon()),
                prefundingClient(Map.of("SENDMN", movements), false), backlog(0, 0, 0), "SENDMN").build(D);

        DayCloseReport.PrefundingSummary p = report.prefunding().get(0);
        assertThat(p.movementCount()).isEqualTo(3);
        assertThat(p.floatNetUsd())
                .as("signed netting: deduct + reverse + retained slice = -800")
                .isEqualByComparingTo("-800");
        assertThat(p.floatConsumedUsd()).isEqualByComparingTo("800");
        assertThat(p.txnDeductedUsd()).isEqualByComparingTo("800");
        assertThat(p.delta()).isEqualByComparingTo("0");
        assertThat(report.variances()).isEmpty();
    }

    @Test
    @DisplayName("transactions whose USD no float movement carries are named — the one-sided failure")
    void transactionsWithNoMatchingFloatMovementAreNamed() {
        DayCloseReport report = service(
                txnClient(List.of(
                        txn("T1", "sendmn", "KRW", "1350000", "MNT", "3000000", "1000"),
                        txn("T2", "sendmn", "KRW", "675000", "MNT", "1500000", "500")), false),
                ledgerClient(balancedLedger(), cleanRecon()),
                prefundingClient(Map.of("SENDMN", List.of(movement("T1", "-1000"))), false),
                backlog(0, 0, 0), "SENDMN").build(D);

        DayCloseReport.Variance v =
                variance(report, DayCloseReportService.VAR_TXN_USD_WITHOUT_FLOAT_MOVEMENT);
        assertThat(v.delta()).isEqualByComparingTo("500");
        assertThat(v.treatment()).isEqualTo(DayCloseReport.Variance.Treatment.DEFECT_TO_INVESTIGATE);
        assertThat(v.note()).contains("1 transaction(s)");
    }

    @Test
    @DisplayName("the replay backlog is ON the report — a ledger-only report cannot see a dropped posting")
    void theReplayBacklogIsOnTheReport() {
        DayCloseReport report = service(
                txnClient(List.of(txn("T1", "sendmn", "KRW", "1350000", "MNT", "3000000", "1000")), false),
                ledgerClient(balancedLedger(), cleanRecon()),
                prefundingClient(Map.of("SENDMN", List.of(movement("T1", "-1000"))), false),
                backlog(2, 1, 7), "SENDMN").build(D);

        assertThat(report.postingBacklog().outstanding()).isEqualTo(3);
        assertThat(report.postingBacklog().pending()).isEqualTo(2);
        assertThat(report.postingBacklog().poison()).isEqualTo(1);
        assertThat(report.postingBacklog().oldestOutstandingAt()).isNotNull();

        DayCloseReport.Variance v = variance(report, DayCloseReportService.VAR_REVENUE_POSTINGS_OUTSTANDING);
        assertThat(v.delta()).isEqualByComparingTo("3");
        assertThat(v.note())
                .as("the ledger balances perfectly BECAUSE the postings are missing — that has to be said")
                .contains("understates revenue and still");
        assertThat(report.clean()).isFalse();
    }

    @Test
    @DisplayName("a ledger imbalance and an untied revenue stream are quoted, not recomputed")
    void ledgerFindingsAreQuotedAsVariances() {
        RevenueLedgerReportClient.CurrencyTotals broken =
                new RevenueLedgerReportClient.CurrencyTotals("USD", new BigDecimal("10.00"),
                        new BigDecimal("9.00"), new BigDecimal("1.00"), false, 3);
        RevenueLedgerReportClient.TrialBalance tb = new RevenueLedgerReportClient.TrialBalance(false,
                List.of(broken), List.of(broken), List.of());
        RevenueLedgerReportClient.JournalReconciliation jr =
                new RevenueLedgerReportClient.JournalReconciliation(false,
                        new RevenueLedgerReportClient.Coverage("revenue_records", 5, 3, 2, 0,
                                List.of("T9", "T10"), false),
                        new RevenueLedgerReportClient.Coverage("commission_splits", 0, 0, 0, 0, List.of(),
                                false),
                        List.of(new RevenueLedgerReportClient.TieOut("FX_MARGIN", "REVENUE_FX_MARGIN", "USD",
                                new BigDecimal("5.00"), new BigDecimal("4.00"), new BigDecimal("1.00"),
                                false)),
                        List.of());

        DayCloseReport report = service(txnClient(List.of(), false), ledgerClient(tb, jr),
                prefundingClient(Map.of(), false), backlog(0, 0, 0), "").build(D);

        assertThat(variance(report, DayCloseReportService.VAR_LEDGER_TRIAL_BALANCE_IMBALANCE).delta())
                .isEqualByComparingTo("1.00");
        assertThat(variance(report, DayCloseReportService.VAR_REVENUE_RECORDED_VS_JOURNALLED).dimension())
                .isEqualTo("stream=FX_MARGIN");
        assertThat(variance(report, DayCloseReportService.VAR_REVENUE_NOT_JOURNALLED).delta())
                .isEqualByComparingTo("2");
        assertThat(report.ledger().untiedStreams()).containsExactly("FX_MARGIN");
        assertThat(report.ledger().recordsNotJournalled()).isEqualTo(2);
        assertThat(report.clean()).isFalse();
    }
}
