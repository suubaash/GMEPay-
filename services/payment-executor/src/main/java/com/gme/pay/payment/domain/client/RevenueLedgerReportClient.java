package com.gme.pay.payment.domain.client;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Read seam onto revenue-ledger's EXISTING finance reports — leg (b) of the day-close tie-out (gap
 * <b>T2-5</b>).
 *
 * <h2>Why a read seam and not a re-implementation</h2>
 * <p>T2-4 already built the two artifacts the day-close needs and got them right: the per-currency
 * <b>trial balance</b> ({@code GET /v1/journals/trial-balance}) and the records-vs-journal
 * <b>reconciliation</b> ({@code GET /v1/revenue/journal-reconciliation}, which is also where the T2-10 unmapped
 * partner-commission carve is surfaced). Recomputing either here would mean a second implementation of the
 * ledger's own arithmetic living outside the ledger, free to drift from it — and the drift would show up as a
 * day-close variance that did not exist. So the day-close CONSUMES those endpoints and quotes them.
 *
 * <p>Deliberately separate from {@link RevenueLedgerClient}: that interface is the WRITE side and its contract
 * is "never throw, swallow everything" (a ledger outage must not fail a payment). A report that swallowed a
 * failed read would print zeros, which is the one thing a reconciliation must never do. Every method here
 * throws, and the day-close marks the leg UNAVAILABLE.
 */
public interface RevenueLedgerReportClient {

    /**
     * revenue-ledger's trial balance for {@code [start, end]}.
     *
     * @throws RuntimeException when revenue-ledger cannot be read; the caller marks the leg unavailable
     */
    TrialBalance fetchTrialBalance(LocalDate start, LocalDate end);

    /**
     * revenue-ledger's revenue-records ↔ journal reconciliation for {@code [start, end]}.
     *
     * @throws RuntimeException when revenue-ledger cannot be read; the caller marks the leg unavailable
     */
    JournalReconciliation fetchJournalReconciliation(LocalDate start, LocalDate end);

    /**
     * The subset of revenue-ledger's {@code TrialBalanceView} the day-close quotes.
     *
     * @param balanced   true iff every currency's debits equal its credits exactly
     * @param currencies per-currency debit/credit totals
     * @param imbalances the subset of {@code currencies} that did NOT balance
     * @param accounts   per-account, per-currency movements. Carried because a variance whose treatment is an
     *                   open decision is detectable only at account level — e.g. T2-11's double relief of
     *                   {@code RECEIVABLE_PARTNER} leaves every currency perfectly balanced while the
     *                   receivable itself drifts, so the per-currency totals cannot see it
     */
    record TrialBalance(boolean balanced, List<CurrencyTotals> currencies,
                        List<CurrencyTotals> imbalances, List<AccountRow> accounts) {

        /** One account's movement in one currency, or null when the period has none. */
        public AccountRow account(String account, String currency) {
            if (accounts == null) {
                return null;
            }
            return accounts.stream()
                    .filter(a -> account.equals(a.account()) && currency.equals(a.currency()))
                    .findFirst()
                    .orElse(null);
        }

        /** Every currency in which {@code account} moved this period. */
        public List<AccountRow> account(String account) {
            return accounts == null ? List.of()
                    : accounts.stream().filter(a -> account.equals(a.account())).toList();
        }
    }

    /** Whole-book totals for one currency, as revenue-ledger computed them. */
    record CurrencyTotals(String currency, BigDecimal debitTotal, BigDecimal creditTotal,
                          BigDecimal difference, boolean balanced, long lineCount) {}

    /**
     * One account's period movement in one currency, as revenue-ledger computed it.
     *
     * @param balance {@code debitTotal − creditTotal}; an account may legitimately be non-zero
     */
    record AccountRow(String account, String currency, BigDecimal debitTotal, BigDecimal creditTotal,
                      BigDecimal balance, long lineCount) {}

    /**
     * The subset of revenue-ledger's {@code RevenueJournalReconciliationView} the day-close quotes.
     *
     * @param clean              revenue-ledger's own verdict — false while ANY unmapped amount carries money
     * @param revenueRecords     coverage of {@code revenue_records}
     * @param commissionSplits   coverage of {@code commission_splits}
     * @param tieOuts            per-stream recorded-vs-journalled comparison
     * @param unmappedComponents recorded money with no account code — the T2-10 open decision
     */
    record JournalReconciliation(boolean clean, Coverage revenueRecords, Coverage commissionSplits,
                                 List<TieOut> tieOuts, List<UnmappedComponent> unmappedComponents) {}

    /** How many of one record type's rows reached the journal. */
    record Coverage(String source, long total, long journalled, long notJournalled, long zeroAmount,
                    List<String> notJournalledTxnRefs, boolean truncated) {}

    /** One revenue stream's recorded total vs what the journal credited. */
    record TieOut(String stream, String account, String currency, BigDecimal recordedAmount,
                  BigDecimal journalledAmount, BigDecimal variance, boolean tied) {}

    /** Money that is recorded but has no account code to be journalled against. */
    record UnmappedComponent(String component, String source, String currency, BigDecimal amount,
                             long recordCount, String reason, String decisionRequired) {}
}
