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
        long partnerShare = splits[3];
        if (gmeGross != 0 || commissionSplits.countByRevenueDateBetween(start, end) > 0) {
            out.add(tieOut("GME_FEE_SHARE", ChartOfAccounts.REVENUE_GME_FEE_SHARE, KRW,
                    BigDecimal.valueOf(gmeGross),
                    commissionSplits.sumJournalledCreditForSplitsInRange(
                            ChartOfAccounts.REVENUE_GME_FEE_SHARE, KRW, start, end)));
        }
        // T2-10: the partner carve is now booked (DR EXPENSE_PARTNER_COMMISSION / CR PAYABLE_PARTNER), so
        // it gets a tie-out like every other stream: recorded partner_share_krw vs what the journal
        // actually credited to the payable. Only reported when the period has a carve to tie out.
        if (partnerShare != 0) {
            out.add(tieOut("PARTNER_COMMISSION_CARVE", ChartOfAccounts.PAYABLE_PARTNER, KRW,
                    BigDecimal.valueOf(partnerShare),
                    commissionSplits.sumJournalledCreditForSplitsInRange(
                            ChartOfAccounts.PAYABLE_PARTNER, KRW, start, end)));
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
     * Money recorded in the period that is NOT on the double-entry books.
     *
     * <p>One entry, {@code PARTNER_COMMISSION_SHARE} — the PARTNER-side leg of the commission split
     * ({@code commission_splits.partner_share_krw}). <b>T2-10 decided its treatment</b>: GME collects the
     * whole merchant fee and the carve is a fraction of GME's own resulting commission, so it is a cost
     * GME pays the partner and is booked {@code DEBIT EXPENSE_PARTNER_COMMISSION / CREDIT
     * PAYABLE_PARTNER}.
     *
     * <p>The entry therefore no longer reports the whole recorded carve — it reports only the carve that
     * is <b>actually still unbooked</b>: splits carrying a carve with no {@code PAYABLE_PARTNER} credit
     * (rows written before T2-10 and not yet replayed). That total is zero for any period whose splits
     * were all journalled, which is what lets {@code clean} become true — and it is non-zero, with the
     * offending count, whenever real money is genuinely missing from the books. The amount is summed over
     * the exception rows rather than derived as recorded-minus-journalled, so a reversal's mirroring DEBIT
     * of {@code PAYABLE_PARTNER} can never make a properly booked carve reappear here.
     */
    private List<UnmappedComponent> unmappedComponents(LocalDate start, LocalDate end) {
        long unbooked = commissionSplits.sumUnjournalledPartnerCarveInRange(
                start, end, ChartOfAccounts.PAYABLE_PARTNER);
        long unbookedCount = commissionSplits.countSplitsMissingPartnerCarveJournal(
                start, end, ChartOfAccounts.PAYABLE_PARTNER);
        return List.of(new UnmappedComponent(
                "PARTNER_COMMISSION_SHARE",
                "commission_splits.partner_share_krw",
                KRW,
                BigDecimal.valueOf(unbooked),
                unbookedCount,
                unbooked == 0
                        ? "Nothing outstanding: every commission split in this period that carries a "
                                + "partner carve has been journalled as DEBIT EXPENSE_PARTNER_COMMISSION / "
                                + "CREDIT PAYABLE_PARTNER (T2-10)."
                        : "These splits carry a partner commission carve with no PAYABLE_PARTNER credit, "
                                + "so that money is not on the double-entry books. The accounting "
                                + "treatment is decided (T2-10: expense + payable); these rows predate it "
                                + "and their journal has not been back-filled yet.",
                unbooked == 0
                        ? "None — T2-10 is resolved."
                        : "No decision outstanding. Re-post the affected splits (recording a split is "
                                + "idempotent and back-fills the missing carve journal on replay)."));
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
