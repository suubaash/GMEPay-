package com.gme.pay.ledger.persistence;

import com.gme.pay.ledger.web.RevenueJournalReconciliationView;
import com.gme.pay.ledger.web.RevenueJournalReconciliationView.Coverage;
import com.gme.pay.ledger.web.RevenueJournalReconciliationView.TieOut;
import com.gme.pay.ledger.web.RevenueJournalReconciliationView.UnmappedComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The T2-4 self-check the finance team runs for a period: does every recorded revenue row have journal
 * lines, do the amounts tie, and what money is recorded that could not be journalled at all.
 *
 * <p>Complements {@link TrialBalanceService}. The trial balance proves the journal is internally
 * consistent (debits == credits); this proves the journal is COMPLETE with respect to the revenue
 * subledgers. A period can pass one and fail the other, which is precisely the failure T2-4 was about —
 * before it, the journal balanced perfectly while containing none of the main P&amp;L.
 *
 * <p><b>Nothing here is silent.</b> Missing journals are listed by reference, amount variances are
 * reported per stream, and money that has no account code to be booked against is listed as an
 * {@link UnmappedComponent} with its period total, so the size of the open accounting decision is
 * visible rather than inferred. Any finding logs at WARN.
 */
@Service
public class RevenueJournalReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(RevenueJournalReconciliationService.class);

    /** Cap on how many exception references one report lists (the counts are always exact). */
    static final int MAX_LISTED_EXCEPTIONS = 100;

    /** Currency the commission split is always denominated in (ZeroPay settles the merchant fee in KRW). */
    private static final String KRW = "KRW";
    /** Reporting/settlement currency FX margin is denominated in. */
    private static final String USD = "USD";

    /** Income accounts an original revenue capture credits — the "is it journalled" probe. */
    private static final Set<String> CAPTURE_INCOME_ACCOUNTS =
            Set.of(ChartOfAccounts.REVENUE_FX_MARGIN, ChartOfAccounts.REVENUE_SERVICE_CHARGE);

    private final RevenueRecordJpaRepository revenueRecords;
    private final CommissionSplitRecordRepository commissionSplits;

    public RevenueJournalReconciliationService(RevenueRecordJpaRepository revenueRecords,
                                               CommissionSplitRecordRepository commissionSplits) {
        this.revenueRecords = Objects.requireNonNull(revenueRecords, "revenueRecords repo required");
        this.commissionSplits = Objects.requireNonNull(commissionSplits, "commissionSplits repo required");
    }

    /**
     * Reconcile revenue records and commission splits against journal lines for the inclusive revenue-date
     * range {@code [start, end]}.
     *
     * @throws IllegalArgumentException if {@code start > end}
     */
    @Transactional(readOnly = true)
    public RevenueJournalReconciliationView reconcile(LocalDate start, LocalDate end) {
        Objects.requireNonNull(start, "start required");
        Objects.requireNonNull(end, "end required");
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("start must be <= end, got start=" + start + " end=" + end);
        }

        Coverage recordCoverage = revenueRecordCoverage(start, end);
        Coverage splitCoverage = commissionSplitCoverage(start, end);
        List<TieOut> tieOuts = tieOuts(start, end);
        List<UnmappedComponent> unmapped = unmappedComponents(start, end);

        boolean clean = recordCoverage.notJournalled() == 0
                && splitCoverage.notJournalled() == 0
                && tieOuts.stream().allMatch(TieOut::tied)
                && unmapped.stream().allMatch(u -> u.amount().signum() == 0);

        if (!clean) {
            log.warn("revenue↔journal reconciliation NOT clean for [{}..{}]: recordsNotJournalled={} "
                            + "splitsNotJournalled={} untiedStreams={} unmappedComponents={}",
                    start, end, recordCoverage.notJournalled(), splitCoverage.notJournalled(),
                    tieOuts.stream().filter(t -> !t.tied()).map(TieOut::stream).toList(),
                    unmapped.stream().filter(u -> u.amount().signum() != 0)
                            .map(UnmappedComponent::component).toList());
        }

        return new RevenueJournalReconciliationView(
                start, end, recordCoverage, splitCoverage, tieOuts, unmapped, clean);
    }

    /** Coverage of {@code revenue_records}: FX margin + service charge vs their capture journals. */
    private Coverage revenueRecordCoverage(LocalDate start, LocalDate end) {
        long total = revenueRecords.countByRevenueDateBetween(start, end);
        long zeroAmount = revenueRecords.countZeroRevenueInRange(start, end);
        // Fetch one more than the cap so we can report truncation honestly.
        List<String> missing = revenueRecords.findTxnRefsMissingCaptureJournal(
                start, end, CAPTURE_INCOME_ACCOUNTS, PageRequest.of(0, MAX_LISTED_EXCEPTIONS + 1));
        return coverage("revenue_records", total, zeroAmount, missing);
    }

    /** Coverage of {@code commission_splits}: the scheme-side leg vs its fee-share journals. */
    private Coverage commissionSplitCoverage(LocalDate start, LocalDate end) {
        long total = commissionSplits.countByRevenueDateBetween(start, end);
        long zeroAmount = commissionSplits.countZeroFeeInRange(start, end);
        List<String> missing = commissionSplits.findTxnRefsMissingFeeShareJournal(
                start, end, ChartOfAccounts.REVENUE_GME_FEE_SHARE,
                PageRequest.of(0, MAX_LISTED_EXCEPTIONS + 1));
        return coverage("commission_splits", total, zeroAmount, missing);
    }

    /**
     * Assemble a {@link Coverage}. {@code notJournalled} is the exact count when it fits under the cap;
     * when the page came back full we cannot know the exact number without an extra count query, so the
     * count is reported as the capped size and {@code truncated} is set — never silently rounded.
     */
    private static Coverage coverage(String source, long total, long zeroAmount, List<String> missing) {
        boolean truncated = missing.size() > MAX_LISTED_EXCEPTIONS;
        List<String> listed = truncated ? List.copyOf(missing.subList(0, MAX_LISTED_EXCEPTIONS))
                                        : List.copyOf(missing);
        long notJournalled = listed.size();
        long journalled = Math.max(0, total - zeroAmount - notJournalled);
        return new Coverage(source, total, journalled, notJournalled, zeroAmount, listed, truncated);
    }

    /**
     * Per-stream recorded-vs-journalled amount tie-outs: FX margin (USD), service charge (every currency
     * the period's records actually used) and the GME fee share (KRW).
     */
    private List<TieOut> tieOuts(LocalDate start, LocalDate end) {
        List<TieOut> out = new ArrayList<>();

        out.add(tieOut("FX_MARGIN", ChartOfAccounts.REVENUE_FX_MARGIN, USD,
                revenueRecords.sumFxMarginUsdInRange(start, end),
                revenueRecords.sumJournalledCreditForRecordsInRange(
                        ChartOfAccounts.REVENUE_FX_MARGIN, USD, start, end)));

        // Service charge is per-currency; iterate the currencies the records actually carry so a KRW-only
        // period does not report a phantom USD row (and a multi-currency period is not collapsed).
        Set<String> serviceChargeCcys = new LinkedHashSet<>();
        for (Object[] row : revenueRecords.sumServiceChargeByCurrencyInRange(start, end)) {
            String currency = (String) row[0];
            serviceChargeCcys.add(currency);
            out.add(tieOut("SERVICE_CHARGE", ChartOfAccounts.REVENUE_SERVICE_CHARGE, currency,
                    orZero((BigDecimal) row[1]),
                    revenueRecords.sumJournalledCreditForRecordsInRange(
                            ChartOfAccounts.REVENUE_SERVICE_CHARGE, currency, start, end)));
        }

        long[] splits = splitTotals(start, end);
        long gmeGross = splits[1];
        if (gmeGross != 0 || commissionSplits.countByRevenueDateBetween(start, end) > 0) {
            out.add(tieOut("GME_FEE_SHARE", ChartOfAccounts.REVENUE_GME_FEE_SHARE, KRW,
                    BigDecimal.valueOf(gmeGross),
                    commissionSplits.sumJournalledCreditForSplitsInRange(
                            ChartOfAccounts.REVENUE_GME_FEE_SHARE, KRW, start, end)));
        }
        return List.copyOf(out);
    }

    private static TieOut tieOut(String stream, String account, String currency,
                                 BigDecimal recorded, BigDecimal journalled) {
        BigDecimal recordedAmount = orZero(recorded);
        BigDecimal journalledAmount = orZero(journalled);
        BigDecimal variance = recordedAmount.subtract(journalledAmount);
        // signum(), not equals(): the two sides come from NUMERIC columns of different scale
        // (records 20,4 vs ledger 20,8), so 500 and 500.00000000 must count as tied.
        return new TieOut(stream, account, currency, recordedAmount, journalledAmount,
                variance, variance.signum() == 0);
    }

    /**
     * Money recorded in the period that has NO account code in this module to be journalled against.
     *
     * <p>Currently one entry: the PARTNER-side leg of the commission split
     * ({@code commission_splits.partner_share_krw}) — the wallet partner's carve out of GME's gross
     * commission. Booking it needs an account this module does not define (a partner-commission payable
     * or commission-expense account); which account, and therefore whether the carve is a cost of revenue
     * or a reduction of it, is a finance-owner decision, so it is reported here rather than guessed. Note
     * the direct consequence: while this is unmapped, {@code REVENUE_GME_FEE_SHARE} carries GME's GROSS
     * commission and therefore overstates retained commission by exactly this amount.
     */
    private List<UnmappedComponent> unmappedComponents(LocalDate start, LocalDate end) {
        long[] splits = splitTotals(start, end);
        long partnerShare = splits[3];
        long splitCount = commissionSplits.countByRevenueDateBetween(start, end);
        return List.of(new UnmappedComponent(
                "PARTNER_COMMISSION_SHARE",
                "commission_splits.partner_share_krw",
                KRW,
                BigDecimal.valueOf(partnerShare),
                splitCount,
                "The partner-side leg of the two-sided commission split has no account code in this "
                        + "module's chart of accounts, so no balanced journal can be posted for it without "
                        + "inventing one. REVENUE_GME_FEE_SHARE therefore carries GME's GROSS commission "
                        + "and overstates retained commission by this amount.",
                "Finance owner must designate the account(s) the partner's commission carve debits and "
                        + "credits (e.g. a partner-commission payable and its expense/contra-revenue "
                        + "counterpart) before it can be journalled."));
    }

    /** {@code [net, gmeGross, scheme, partner, gmeNet]} KRW totals for the period (zeros when no rows). */
    private long[] splitTotals(LocalDate start, LocalDate end) {
        List<Object[]> rows = commissionSplits.sumSplitAmountsInRange(start, end);
        if (rows.isEmpty() || rows.get(0) == null) {
            return new long[]{0, 0, 0, 0, 0};
        }
        Object[] r = rows.get(0);
        long[] out = new long[5];
        for (int i = 0; i < 5; i++) {
            out[i] = r[i] == null ? 0L : ((Number) r[i]).longValue();
        }
        return out;
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
