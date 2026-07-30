package com.gme.pay.payment.dayclose;

import com.gme.pay.payment.domain.client.PrefundingClient;
import com.gme.pay.payment.domain.client.RevenueLedgerReportClient;
import com.gme.pay.payment.domain.client.TransactionClient;
import com.gme.pay.payment.opsrun.LedgerOpsJob;
import com.gme.pay.payment.opsrun.LedgerOpsRunExecutor;
import com.gme.pay.payment.opsrun.LedgerOpsRunRecorder;
import com.gme.pay.payment.opsrun.LedgerOpsRunTrigger;
import com.gme.pay.payment.replay.RevenuePostingOutstandingQuery;
import com.gme.pay.payment.replay.RevenuePostingOutstandingView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the three-way {@link DayCloseReport} for one business date (gap <b>T2-5</b> / CFO#10).
 *
 * <h2>Reuse, not re-implementation</h2>
 * <p>The ledger leg is QUOTED from revenue-ledger's existing trial-balance and journal-reconciliation endpoints
 * via {@link RevenueLedgerReportClient}. Its {@code balanced} and {@code clean} flags are carried through
 * verbatim and are never overridden — if the ledger says the period is not clean, neither is the close. The
 * corridor tie-out T2-2 built for SENDMN is likewise NOT duplicated: this is the platform-wide REPORTING layer
 * over legs that already exist, and per-corridor scheme confirmations remain settlement-reconciliation's job
 * (which is another service and another owner).
 *
 * <h2>No accounting policy is invented here</h2>
 * <p>The service computes differences and labels them. Where a difference's TREATMENT is an open decision it is
 * marked {@link DayCloseReport.Variance.Treatment#UNRESOLVED_DECISION} and repeated in
 * {@code unresolvedDecisions} with the register item and the actual question — T2-10 (the unmapped partner-side
 * commission carve, quoted straight out of revenue-ledger's own {@code unmappedComponents}) and T2-11 (how a
 * refund backs out captured revenue, detected from the presence of {@code REVENUE_REVERSAL} movement on the
 * date). Neither is netted, adjusted or resolved by this code, and both hold {@code clean} false while they carry
 * money.
 *
 * <h2>Failure policy: legs fail independently, and absence is not zero</h2>
 * <p>Each leg is read in its own try/catch. A leg that cannot be read is marked
 * {@code available=false} with the reason, its figures are omitted rather than zeroed, and {@code clean} is
 * false. The report still gets produced from the legs that DID load, because two legs out of three is a useful
 * artifact and no artifact at all is not.
 */
@Service
public class DayCloseReportService {

    private static final Logger log = LoggerFactory.getLogger(DayCloseReportService.class);

    /** revenue-ledger account codes this report reads by name. Mirrors its {@code ChartOfAccounts}. */
    private static final String ACC_RECEIVABLE_PARTNER = "RECEIVABLE_PARTNER";
    private static final String ACC_REVENUE_REVERSAL = "REVENUE_REVERSAL";

    // ---- variance codes (stable: a monitor may alert on a specific one) ----
    /** A partner's float movement disagrees with the USD its transactions say was deducted. */
    public static final String VAR_FLOAT_VS_TRANSACTION_USD = "FLOAT_VS_TRANSACTION_USD";
    /** Transactions claim USD left the float but no float movement carries their reference. */
    public static final String VAR_TXN_USD_WITHOUT_FLOAT_MOVEMENT = "TXN_USD_WITHOUT_FLOAT_MOVEMENT";
    /** A currency's journal debits do not equal its credits. */
    public static final String VAR_LEDGER_TRIAL_BALANCE_IMBALANCE = "LEDGER_TRIAL_BALANCE_IMBALANCE";
    /** A revenue stream's recorded total disagrees with what the journal credited. */
    public static final String VAR_REVENUE_RECORDED_VS_JOURNALLED = "REVENUE_RECORDED_VS_JOURNALLED";
    /** Revenue rows carrying money reached no journal at all. */
    public static final String VAR_REVENUE_NOT_JOURNALLED = "REVENUE_NOT_JOURNALLED";
    /** Postings that never reached revenue-ledger and are still outstanding (the T2-5 replay queue). */
    public static final String VAR_REVENUE_POSTINGS_OUTSTANDING = "REVENUE_POSTINGS_OUTSTANDING";
    /** T2-10: recorded money with no account code, so it is not on the double-entry books. */
    public static final String VAR_UNMAPPED_COMPONENT = "UNMAPPED_COMPONENT";
    /** T2-11: revenue was reversed on this date and the reversal's booking treatment is undecided. */
    public static final String VAR_REFUND_REVERSAL_TREATMENT = "REFUND_REVERSAL_TREATMENT";

    private final TransactionClient transactions;
    private final RevenueLedgerReportClient ledgerReports;
    private final PrefundingClient prefunding;
    private final RevenuePostingOutstandingQuery backlogQuery;
    private final FxExposureService fxExposureService;
    private final DayCloseReportStore store;
    private final LedgerOpsRunExecutor executor;
    private final Clock clock;
    private final ZoneId closeZone;
    private final List<String> partnerCodes;

    public DayCloseReportService(
            TransactionClient transactions,
            RevenueLedgerReportClient ledgerReports,
            PrefundingClient prefunding,
            RevenuePostingOutstandingQuery backlogQuery,
            FxExposureService fxExposureService,
            DayCloseReportStore store,
            LedgerOpsRunExecutor executor,
            Clock clock,
            @Value("${gmepay.day-close.zone:Asia/Seoul}") String closeZone,
            @Value("${gmepay.day-close.partner-codes:}") String partnerCodes) {
        this.transactions = transactions;
        this.ledgerReports = ledgerReports;
        this.prefunding = prefunding;
        this.backlogQuery = backlogQuery;
        this.fxExposureService = fxExposureService;
        this.store = store;
        this.executor = executor;
        this.clock = clock;
        this.closeZone = ZoneId.of(closeZone);
        this.partnerCodes = parseCodes(partnerCodes);
    }

    private static List<String> parseCodes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    /** The business date that is closeable now: yesterday in the close timezone. */
    public LocalDate defaultCloseDate() {
        return LocalDate.now(clock.withZone(closeZone)).minusDays(1);
    }

    /**
     * Build, persist and return the close for {@code businessDate}, wrapped in the durable run ledger + failure
     * alerting.
     */
    public LedgerOpsRunExecutor.RunResult<DayCloseReport> run(LocalDate businessDate,
                                                            LedgerOpsRunTrigger trigger,
                                                            @Nullable String operatorId) {
        LedgerOpsRunRecorder.RunKey key = trigger == LedgerOpsRunTrigger.OPERATOR
                ? LedgerOpsRunRecorder.RunKey.operator(LedgerOpsJob.DAY_CLOSE, businessDate, operatorId)
                : LedgerOpsRunRecorder.RunKey.scheduled(LedgerOpsJob.DAY_CLOSE, businessDate);
        return executor.execute(key, () -> {
            DayCloseReport report = build(businessDate);
            store.upsert(report, trigger);
            return report;
        }, r -> LedgerOpsRunExecutor.RunSummary.of(
                "clean=" + r.clean() + " variances=" + r.variances().size()
                        + " unresolvedDecisions=" + r.unresolvedDecisions().size()
                        + " unavailableLegs=" + r.legs().stream().filter(l -> !l.available()).count(),
                r.corridors().stream().mapToInt(DayCloseReport.CorridorSummary::txnCount).sum()));
    }

    /**
     * Build the report for {@code businessDate} without persisting it. Visible for tests and for a read-only
     * preview; {@link #run} is the path that stores the artifact.
     */
    public DayCloseReport build(LocalDate businessDate) {
        if (businessDate == null) {
            throw new IllegalArgumentException("businessDate required");
        }
        Instant now = Instant.now(clock);
        List<DayCloseReport.Leg> legs = new ArrayList<>(3);
        List<DayCloseReport.Variance> variances = new ArrayList<>();
        List<DayCloseReport.UnresolvedDecision> decisions = new ArrayList<>();

        // ---- leg (a): transactions ----
        List<TransactionClient.DailyTransaction> txns = List.of();
        boolean txnLegOk = false;
        try {
            txns = transactions.findApprovedForDate(businessDate);
            txnLegOk = true;
            legs.add(DayCloseReport.Leg.ok(DayCloseReport.LEG_TRANSACTIONS,
                    "transaction-mgmt GET /v1/transactions?status=APPROVED"));
        } catch (RuntimeException e) {
            legs.add(DayCloseReport.Leg.unavailable(DayCloseReport.LEG_TRANSACTIONS,
                    "transaction-mgmt GET /v1/transactions?status=APPROVED", String.valueOf(e)));
            log.error("day-close {}: transaction leg unavailable: {}", businessDate, e.toString());
        }

        List<DayCloseReport.CorridorSummary> corridors = summariseCorridors(txns);

        // ---- leg (b): the ledger journal, QUOTED from revenue-ledger ----
        RevenueLedgerReportClient.TrialBalance trialBalance = null;
        RevenueLedgerReportClient.JournalReconciliation recon = null;
        try {
            trialBalance = ledgerReports.fetchTrialBalance(businessDate, businessDate);
            recon = ledgerReports.fetchJournalReconciliation(businessDate, businessDate);
            legs.add(DayCloseReport.Leg.ok(DayCloseReport.LEG_LEDGER_JOURNAL,
                    "revenue-ledger GET /v1/journals/trial-balance + GET /v1/revenue/journal-reconciliation"));
        } catch (RuntimeException e) {
            legs.add(DayCloseReport.Leg.unavailable(DayCloseReport.LEG_LEDGER_JOURNAL,
                    "revenue-ledger GET /v1/journals/trial-balance + GET /v1/revenue/journal-reconciliation",
                    String.valueOf(e)));
            log.error("day-close {}: ledger leg unavailable: {}", businessDate, e.toString());
        }

        List<DayCloseReport.CurrencySummary> currencies = summariseCurrencies(txns, trialBalance);
        DayCloseReport.LedgerSummary ledgerSummary = summariseLedger(trialBalance, recon);

        if (trialBalance != null) {
            for (RevenueLedgerReportClient.CurrencyTotals c : trialBalance.imbalances()) {
                variances.add(new DayCloseReport.Variance(VAR_LEDGER_TRIAL_BALANCE_IMBALANCE,
                        "currency=" + c.currency(), c.currency(),
                        "journal debits", c.debitTotal(), "journal credits", c.creditTotal(),
                        c.difference(), DayCloseReport.Variance.Treatment.DEFECT_TO_INVESTIGATE,
                        "revenue-ledger reports this currency does not balance: ledger lines exist that no "
                                + "balanced journal produced. Quoted from its trial balance, not recomputed."));
            }
        }
        if (recon != null) {
            for (RevenueLedgerReportClient.TieOut t : recon.tieOuts()) {
                if (!t.tied()) {
                    variances.add(new DayCloseReport.Variance(VAR_REVENUE_RECORDED_VS_JOURNALLED,
                            "stream=" + t.stream(), t.currency(),
                            "recorded (" + t.stream() + ")", t.recordedAmount(),
                            "journalled to " + t.account(), t.journalledAmount(), t.variance(),
                            DayCloseReport.Variance.Treatment.DEFECT_TO_INVESTIGATE,
                            "the revenue subledger and the double-entry journal disagree on AMOUNT for this "
                                    + "stream. Quoted from revenue-ledger's journal-reconciliation."));
                }
            }
            long notJournalled = recon.revenueRecords().notJournalled()
                    + recon.commissionSplits().notJournalled();
            if (notJournalled > 0) {
                variances.add(new DayCloseReport.Variance(VAR_REVENUE_NOT_JOURNALLED, "global", null,
                        "revenue rows carrying money", BigDecimal.valueOf(
                                recon.revenueRecords().total() + recon.commissionSplits().total()),
                        "rows with a journal credit", BigDecimal.valueOf(
                                recon.revenueRecords().journalled() + recon.commissionSplits().journalled()),
                        BigDecimal.valueOf(notJournalled),
                        DayCloseReport.Variance.Treatment.DEFECT_TO_INVESTIGATE,
                        "counts, not money: " + notJournalled + " recorded revenue row(s) carrying money have "
                                + "no journal credit. Offending references are on revenue-ledger's "
                                + "journal-reconciliation response."));
            }
            // T2-10 and any future unmapped component: revenue-ledger already quantifies these; the close
            // repeats them as UNRESOLVED rather than deciding an account for them.
            for (RevenueLedgerReportClient.UnmappedComponent u : recon.unmappedComponents()) {
                if (u.amount() == null || u.amount().signum() == 0) {
                    continue;   // an unmapped component carrying no money decides nothing
                }
                variances.add(new DayCloseReport.Variance(VAR_UNMAPPED_COMPONENT,
                        "component=" + u.component(), u.currency(),
                        "recorded (" + u.source() + ")", u.amount(),
                        "on the double-entry books", BigDecimal.ZERO, u.amount(),
                        DayCloseReport.Variance.Treatment.UNRESOLVED_DECISION,
                        "not a defect and not netted: " + u.reason()));
                decisions.add(new DayCloseReport.UnresolvedDecision("T2-10", u.component(), u.currency(),
                        u.amount(), u.decisionRequired(),
                        "this amount is NOT on the double-entry books, so the income account it would have "
                                + "reduced or offset is overstated by exactly it. Reported every period until "
                                + "the account is decided."));
            }
            // T2-11: detected from the ledger's own account movement rather than from a refund count, because
            // a refund of an earlier date's payment books on THIS date and a transaction-side count would miss it.
            if (trialBalance != null) {
                for (RevenueLedgerReportClient.AccountRow reversal
                        : trialBalance.account(ACC_REVENUE_REVERSAL)) {
                    if (reversal.debitTotal() == null || reversal.debitTotal().signum() == 0) {
                        continue;
                    }
                    RevenueLedgerReportClient.AccountRow receivable =
                            trialBalance.account(ACC_RECEIVABLE_PARTNER, reversal.currency());
                    BigDecimal receivableDrift = receivable == null ? null
                            : orZero(receivable.creditTotal()).subtract(orZero(receivable.debitTotal()));
                    variances.add(new DayCloseReport.Variance(VAR_REFUND_REVERSAL_TREATMENT,
                            "account=" + ACC_REVENUE_REVERSAL, reversal.currency(),
                            "revenue reversed on this date", reversal.debitTotal(),
                            "reversal booked under an agreed policy", BigDecimal.ZERO,
                            reversal.debitTotal(),
                            DayCloseReport.Variance.Treatment.UNRESOLVED_DECISION,
                            "the AMOUNT reversed is not in dispute; how it should be booked is. "
                                    + (receivableDrift == null
                                        ? "RECEIVABLE_PARTNER had no movement in this currency."
                                        : "RECEIVABLE_PARTNER credits exceed debits by " + receivableDrift
                                          + " " + reversal.currency() + " this period, which is the shape of "
                                          + "the double relief T2-11(b) describes.")));
                    decisions.add(new DayCloseReport.UnresolvedDecision("T2-11",
                            "REFUND_REVENUE_REVERSAL", reversal.currency(), reversal.debitTotal(),
                            "How is captured revenue backed out when a payment is refunded — in particular, is "
                                    + "a PARTIAL refund's revenue reversal pro-rated, and which of the two "
                                    + "postings that both credit RECEIVABLE_PARTNER for one reversal is the "
                                    + "correct one?",
                            "the reversal IS booked, so the trial balance still balances, but the receivable is "
                                    + "relieved twice per reversal and a partial refund reverses revenue that "
                                    + "may not be pro-rated. Reported every period until the policy is set."));
                }
            }
        }

        // ---- leg (c): prefunding float movements ----
        Map<String, BigDecimal> txnUsdByRef = new LinkedHashMap<>();
        for (TransactionClient.DailyTransaction t : txns) {
            if (t.txnRef() != null && t.prefundDeductedUsd() != null
                    && t.prefundDeductedUsd().signum() != 0) {
                txnUsdByRef.merge(t.txnRef(), t.prefundDeductedUsd(), BigDecimal::add);
            }
        }
        List<DayCloseReport.PrefundingSummary> prefundingSummaries = new ArrayList<>();
        Set<String> matchedRefs = new HashSet<>();
        boolean prefundingLegOk = false;
        if (partnerCodes.isEmpty()) {
            legs.add(DayCloseReport.Leg.unavailable(DayCloseReport.LEG_PREFUNDING,
                    "prefunding GET /v1/prefunding/{code}/movements",
                    "no partner codes are configured (gmepay.day-close.partner-codes is empty), so there is "
                            + "nothing to read. This is a DEPLOYMENT gap, not an absence of movement: "
                            + "prefunding is keyed by partner CODE and transaction-mgmt does not carry one, "
                            + "so the codes have to be supplied."));
        } else {
            Instant from = businessDate.atStartOfDay(closeZone).toInstant();
            Instant to = businessDate.plusDays(1).atStartOfDay(closeZone).toInstant();
            List<String> failed = new ArrayList<>();
            for (String code : partnerCodes) {
                try {
                    List<PrefundingClient.FloatMovement> movements = prefunding.movements(code, from, to);
                    BigDecimal net = BigDecimal.ZERO;
                    Set<String> refs = new LinkedHashSet<>();
                    for (PrefundingClient.FloatMovement m : movements) {
                        net = net.add(m.delta());
                        String base = baseRef(m.txnRef());
                        if (base != null) {
                            refs.add(base);
                        }
                    }
                    BigDecimal consumed = net.negate();
                    BigDecimal txnDeducted = BigDecimal.ZERO;
                    for (String ref : refs) {
                        BigDecimal usd = txnUsdByRef.get(ref);
                        if (usd != null) {
                            txnDeducted = txnDeducted.add(usd);
                            matchedRefs.add(ref);
                        }
                    }
                    prefundingSummaries.add(new DayCloseReport.PrefundingSummary(code, movements.size(),
                            net, consumed, txnDeducted, txnDeducted.subtract(consumed)));
                } catch (RuntimeException e) {
                    failed.add(code + " (" + e + ")");
                    log.error("day-close {}: prefunding movements for {} unreadable: {}",
                            businessDate, code, e.toString());
                }
            }
            if (failed.isEmpty()) {
                prefundingLegOk = true;
                legs.add(DayCloseReport.Leg.ok(DayCloseReport.LEG_PREFUNDING,
                        "prefunding GET /v1/prefunding/{code}/movements for " + partnerCodes));
            } else {
                // A partially-read float leg cannot support a tie-out: the partners that DID load are
                // reported, but the leg is honestly marked unavailable so nothing is concluded from it.
                legs.add(DayCloseReport.Leg.unavailable(DayCloseReport.LEG_PREFUNDING,
                        "prefunding GET /v1/prefunding/{code}/movements for " + partnerCodes,
                        "could not read " + failed.size() + " of " + partnerCodes.size()
                                + " partner(s): " + failed));
            }
        }

        if (prefundingLegOk && txnLegOk) {
            for (DayCloseReport.PrefundingSummary p : prefundingSummaries) {
                if (p.delta().signum() != 0) {
                    variances.add(new DayCloseReport.Variance(VAR_FLOAT_VS_TRANSACTION_USD,
                            "partner=" + p.partnerCode(), "USD",
                            "USD the transactions say was deducted", p.txnDeductedUsd(),
                            "USD the float actually gave up", p.floatConsumedUsd(), p.delta(),
                            DayCloseReport.Variance.Treatment.DEFECT_TO_INVESTIGATE,
                            "one side moved and the other did not agree. Movements are netted signed, so a "
                                    + "deduct-and-reverse pair is already zero."));
                }
            }
            BigDecimal unmatchedUsd = BigDecimal.ZERO;
            int unmatchedCount = 0;
            for (Map.Entry<String, BigDecimal> e : txnUsdByRef.entrySet()) {
                if (!matchedRefs.contains(e.getKey())) {
                    unmatchedUsd = unmatchedUsd.add(e.getValue());
                    unmatchedCount++;
                }
            }
            if (unmatchedUsd.signum() != 0) {
                variances.add(new DayCloseReport.Variance(VAR_TXN_USD_WITHOUT_FLOAT_MOVEMENT, "global", "USD",
                        "USD claimed deducted with no matching float movement", unmatchedUsd,
                        "matched to a float movement", BigDecimal.ZERO, unmatchedUsd,
                        DayCloseReport.Variance.Treatment.DEFECT_TO_INVESTIGATE,
                        unmatchedCount + " transaction(s) record a prefunding deduction that no configured "
                                + "partner's float movements carry. Either a partner code is missing from "
                                + "gmepay.day-close.partner-codes, or the float never moved."));
            }
        }

        // ---- payment-executor's own replay backlog: the one-sided failure a ledger-only report cannot see ----
        RevenuePostingOutstandingView backlog = backlogQuery.outstanding();
        DayCloseReport.PostingBacklog backlogSummary = new DayCloseReport.PostingBacklog(
                backlog.outstandingCount(), backlog.pendingCount(), backlog.poisonCount(),
                backlog.oldestOutstandingAt());
        if (backlog.outstandingCount() > 0) {
            variances.add(new DayCloseReport.Variance(VAR_REVENUE_POSTINGS_OUTSTANDING, "global", null,
                    "revenue postings NOT on the ledger", BigDecimal.valueOf(backlog.outstandingCount()),
                    "postings replayed successfully", BigDecimal.valueOf(backlog.replayedCount()),
                    BigDecimal.valueOf(backlog.outstandingCount()),
                    DayCloseReport.Variance.Treatment.DEFECT_TO_INVESTIGATE,
                    "counts, not money: " + backlog.pendingCount() + " pending + " + backlog.poisonCount()
                            + " poisoned posting(s) never reached revenue-ledger"
                            + (backlog.oldestOutstandingAt() == null ? ""
                                : ", oldest since " + backlog.oldestOutstandingAt())
                            + ". Until they land, every ledger figure above understates revenue and still "
                            + "balances perfectly — see GET /internal/ops/revenue-posting-failures."));
        }

        // ---- the same date's FX position, embedded so the persisted artifact carries it ----
        FxExposureReport fx;
        try {
            fx = fxExposureService.measure(businessDate, businessDate);
        } catch (RuntimeException e) {
            fx = FxExposureReport.unavailable(businessDate, businessDate, now,
                    "FX exposure could not be measured: " + e);
        }

        boolean allLegsAvailable = legs.stream().allMatch(DayCloseReport.Leg::available);
        boolean clean = variances.isEmpty() && decisions.isEmpty() && allLegsAvailable;

        return new DayCloseReport(businessDate, now, closeZone.getId(), clean, List.copyOf(legs),
                corridors, currencies, ledgerSummary, List.copyOf(prefundingSummaries), backlogSummary, fx,
                List.copyOf(variances), List.copyOf(decisions));
    }

    // ---- summarisers ----

    private static List<DayCloseReport.CorridorSummary> summariseCorridors(
            List<TransactionClient.DailyTransaction> txns) {
        Map<String, Object[]> byCorridor = new LinkedHashMap<>();
        for (TransactionClient.DailyTransaction t : txns) {
            Object[] acc = byCorridor.computeIfAbsent(t.corridor(), k -> new Object[]{
                    t.schemeId(), t.collectionCcy(), t.payoutCcy(), 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0});
            acc[3] = ((Integer) acc[3]) + 1;
            acc[4] = ((BigDecimal) acc[4]).add(orZero(t.collectionAmount()));
            acc[5] = ((BigDecimal) acc[5]).add(orZero(t.payoutAmount()));
            acc[6] = ((BigDecimal) acc[6]).add(t.refunded());
            if (t.prefundDeductedUsd() != null) {
                acc[7] = ((BigDecimal) acc[7]).add(t.prefundDeductedUsd());
            } else {
                acc[8] = ((Integer) acc[8]) + 1;
            }
        }
        List<DayCloseReport.CorridorSummary> out = new ArrayList<>(byCorridor.size());
        for (Map.Entry<String, Object[]> e : byCorridor.entrySet()) {
            Object[] a = e.getValue();
            out.add(new DayCloseReport.CorridorSummary(e.getKey(), (String) a[0], (String) a[1],
                    (String) a[2], (Integer) a[3], (BigDecimal) a[4], (BigDecimal) a[5], (BigDecimal) a[6],
                    (BigDecimal) a[7], (Integer) a[8]));
        }
        return List.copyOf(out);
    }

    private static List<DayCloseReport.CurrencySummary> summariseCurrencies(
            List<TransactionClient.DailyTransaction> txns,
            @Nullable RevenueLedgerReportClient.TrialBalance trialBalance) {
        Map<String, BigDecimal[]> amounts = new LinkedHashMap<>();
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (TransactionClient.DailyTransaction t : txns) {
            if (t.collectionCcy() != null) {
                amounts.computeIfAbsent(t.collectionCcy(), k -> zeroPair())[0] =
                        amounts.get(t.collectionCcy())[0].add(orZero(t.collectionAmount()));
                counts.computeIfAbsent(t.collectionCcy(), k -> new int[2])[0]++;
            }
            if (t.payoutCcy() != null) {
                amounts.computeIfAbsent(t.payoutCcy(), k -> zeroPair())[1] =
                        amounts.get(t.payoutCcy())[1].add(orZero(t.payoutAmount()));
                counts.computeIfAbsent(t.payoutCcy(), k -> new int[2])[1]++;
            }
        }
        // Currencies the journal moved in but no transaction touched are also in scope: a journal-only
        // currency is exactly as interesting as a transaction-only one.
        if (trialBalance != null) {
            for (RevenueLedgerReportClient.CurrencyTotals c : trialBalance.currencies()) {
                amounts.computeIfAbsent(c.currency(), k -> zeroPair());
                counts.computeIfAbsent(c.currency(), k -> new int[2]);
            }
        }

        List<DayCloseReport.CurrencySummary> out = new ArrayList<>(amounts.size());
        for (Map.Entry<String, BigDecimal[]> e : amounts.entrySet()) {
            String ccy = e.getKey();
            int[] c = counts.getOrDefault(ccy, new int[2]);
            RevenueLedgerReportClient.CurrencyTotals journal = trialBalance == null ? null
                    : trialBalance.currencies().stream()
                            .filter(t -> ccy.equals(t.currency())).findFirst().orElse(null);
            out.add(new DayCloseReport.CurrencySummary(ccy, c[0], e.getValue()[0], c[1], e.getValue()[1],
                    journal == null ? null : journal.debitTotal(),
                    journal == null ? null : journal.creditTotal(),
                    journal == null ? null : journal.difference(),
                    journal != null && journal.balanced(),
                    journal == null ? 0L : journal.lineCount()));
        }
        return List.copyOf(out);
    }

    private static DayCloseReport.LedgerSummary summariseLedger(
            @Nullable RevenueLedgerReportClient.TrialBalance trialBalance,
            @Nullable RevenueLedgerReportClient.JournalReconciliation recon) {
        // Absent legs report FALSE, never TRUE: an unread ledger must not answer "balanced".
        boolean balanced = trialBalance != null && trialBalance.balanced();
        boolean clean = recon != null && recon.clean();
        long recordsNot = recon == null ? 0 : recon.revenueRecords().notJournalled();
        long splitsNot = recon == null ? 0 : recon.commissionSplits().notJournalled();
        List<String> untied = recon == null ? List.of() : recon.tieOuts().stream()
                .filter(t -> !t.tied())
                .map(RevenueLedgerReportClient.TieOut::stream)
                .toList();
        return new DayCloseReport.LedgerSummary(balanced, clean, recordsNot, splitsNot, untied);
    }

    /**
     * Normalises a float movement's reference back to the transaction reference it belongs to. The refund path
     * (T2-6) keys its retained-amount slices as {@code <txnRef>#REFUND-RETAINED@<cumulative>}, so an exact match
     * would leave every refund slice unjoined and manufacture a variance.
     */
    private static String baseRef(String txnRef) {
        if (txnRef == null || txnRef.isBlank()) {
            return null;
        }
        int hash = txnRef.indexOf('#');
        return hash < 0 ? txnRef : txnRef.substring(0, hash);
    }

    private static BigDecimal[] zeroPair() {
        return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
