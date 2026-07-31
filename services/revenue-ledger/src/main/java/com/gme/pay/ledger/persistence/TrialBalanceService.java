package com.gme.pay.ledger.persistence;

import com.gme.pay.ledger.web.TrialBalanceView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Computes the period {@link TrialBalanceView} over the posted double-entry journals — the T2-4
 * artifact that proves debits equal credits, per account and per currency, for a date range.
 *
 * <p><b>Why this can now exist.</b> Before T2-4 the journal carried only rounding residuals and
 * cancel/refund reversals; the main P&amp;L lived in the single-entry {@code revenue_records} store, so a
 * trial balance over the journal was meaningless. Revenue capture and the commission split now post
 * balanced journals at capture time, so this report covers the real book.
 *
 * <p><b>What it does not do.</b> It does not assert that revenue RECORDS agree with journal lines —
 * that is the separate {@link RevenueJournalReconciliationService} self-check. A period can be
 * perfectly balanced here while revenue is missing from the journal entirely, which is exactly why both
 * reports exist.
 *
 * <p><b>Loud on imbalance.</b> Every {@code Journal} is validated balanced by
 * {@link com.gme.pay.ledger.domain.model.Journal#post} before it is stored, so an imbalance here means
 * ledger rows exist that no balanced journal produced (direct DB write, a partially-failed insert, or
 * corruption). That is logged at ERROR with the offending currency totals and reported explicitly on
 * the response — never absorbed.
 */
@Service
public class TrialBalanceService {

    private static final Logger log = LoggerFactory.getLogger(TrialBalanceService.class);

    private final LedgerEntryEntityRepository entries;

    public TrialBalanceService(LedgerEntryEntityRepository entries) {
        this.entries = Objects.requireNonNull(entries, "entries repo required");
    }

    /**
     * Build the trial balance for journals posted on the inclusive date range {@code [start, end]}
     * (interpreted in UTC, matching {@code JpaJournalStore.sumRoundingByDateRange}).
     *
     * @throws IllegalArgumentException if {@code start > end}
     */
    @Transactional(readOnly = true)
    public TrialBalanceView compute(LocalDate start, LocalDate end) {
        Objects.requireNonNull(start, "start required");
        Objects.requireNonNull(end, "end required");
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("start must be <= end, got start=" + start + " end=" + end);
        }
        Instant from = start.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toExclusive = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<TrialBalanceView.Row> rows = new ArrayList<>();
        // Accumulate per-currency whole-book totals while walking the grouped rows.
        Map<String, BigDecimal[]> debitCreditByCcy = new LinkedHashMap<>();
        Map<String, Long> lineCountByCcy = new LinkedHashMap<>();

        for (Object[] r : entries.trialBalanceRows(from, toExclusive)) {
            String account = (String) r[0];
            String currency = (String) r[1];
            BigDecimal debit = orZero((BigDecimal) r[2]);
            BigDecimal credit = orZero((BigDecimal) r[3]);
            long lineCount = ((Number) r[4]).longValue();

            rows.add(new TrialBalanceView.Row(
                    account, currency, debit, credit, debit.subtract(credit), lineCount));

            BigDecimal[] totals = debitCreditByCcy
                    .computeIfAbsent(currency, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            totals[0] = totals[0].add(debit);
            totals[1] = totals[1].add(credit);
            lineCountByCcy.merge(currency, lineCount, Long::sum);
        }

        List<TrialBalanceView.CurrencyTotals> currencies = new ArrayList<>(debitCreditByCcy.size());
        List<TrialBalanceView.CurrencyTotals> imbalances = new ArrayList<>();
        boolean allBalanced = true;
        for (Map.Entry<String, BigDecimal[]> e : debitCreditByCcy.entrySet()) {
            BigDecimal debit = e.getValue()[0];
            BigDecimal credit = e.getValue()[1];
            BigDecimal difference = debit.subtract(credit);
            // compareTo, not equals: 0 and 0.00000000 are the same money at different scales.
            boolean balanced = difference.signum() == 0;
            TrialBalanceView.CurrencyTotals totals = new TrialBalanceView.CurrencyTotals(
                    e.getKey(), debit, credit, difference, balanced,
                    lineCountByCcy.getOrDefault(e.getKey(), 0L));
            currencies.add(totals);
            if (!balanced) {
                allBalanced = false;
                imbalances.add(totals);
                log.error("TRIAL BALANCE DOES NOT BALANCE: range=[{}..{}] currency={} debits={} credits={} "
                                + "difference={} — ledger lines exist that no balanced journal produced",
                        start, end, e.getKey(), debit, credit, difference);
            }
        }

        return new TrialBalanceView(start, end, allBalanced,
                List.copyOf(currencies), List.copyOf(rows), List.copyOf(imbalances));
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
