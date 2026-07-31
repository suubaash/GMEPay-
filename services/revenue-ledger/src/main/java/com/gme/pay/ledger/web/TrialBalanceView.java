package com.gme.pay.ledger.web;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The trial balance for a period — the artifact that PROVES the double-entry journal balances, per
 * account and in total, per currency (T2-4 / CFO#7).
 *
 * <p>Wire shape of {@code GET /v1/journals/trial-balance}:
 * <pre>
 *   { "startDate": "2026-07-01", "endDate": "2026-07-31",
 *     "balanced": true,
 *     "currencies": [ { "currency": "USD", "debitTotal": "12.34000000",
 *                       "creditTotal": "12.34000000", "difference": "0.00000000",
 *                       "balanced": true, "lineCount": 4 } ],
 *     "rows": [ { "account": "RECEIVABLE_PARTNER", "currency": "USD",
 *                 "debitTotal": "12.34000000", "creditTotal": "0.00000000",
 *                 "balance": "12.34000000", "lineCount": 2 } ],
 *     "imbalances": [] }
 * </pre>
 *
 * <p>Money rides as decimal STRINGs per {@code docs/MONEY_CONVENTION.md}.
 *
 * <p><b>How to read it.</b> {@code rows} is the per-account trial balance (a single account is NOT
 * expected to net to zero — {@code balance = debitTotal − creditTotal} is its period movement).
 * {@code currencies} is the proof: for each currency the sum of ALL debits must equal the sum of ALL
 * credits, so {@code difference} must be exactly zero. {@code balanced} is the AND of every currency
 * and is the single field a day-close check should assert on; {@code imbalances} repeats only the
 * currencies that failed, so a non-empty list is never something a reader has to go looking for.
 *
 * @param startDate  inclusive first journal post date in scope
 * @param endDate    inclusive last journal post date in scope
 * @param balanced   true iff every currency's debits equal its credits exactly
 * @param currencies per-currency debit/credit totals — the balance proof
 * @param rows       per-account, per-currency movements
 * @param imbalances the subset of {@code currencies} that did NOT balance (empty when healthy)
 */
public record TrialBalanceView(
        LocalDate startDate,
        LocalDate endDate,
        boolean balanced,
        List<CurrencyTotals> currencies,
        List<Row> rows,
        List<CurrencyTotals> imbalances
) {

    /** One account's movement in one currency over the period. */
    public record Row(
            String account,
            String currency,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal debitTotal,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal creditTotal,
            /** {@code debitTotal − creditTotal}; the account's net movement (may legitimately be non-zero). */
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balance,
            long lineCount
    ) {}

    /**
     * Whole-book totals for one currency. {@code difference = debitTotal − creditTotal} and MUST be
     * zero: a non-zero value means ledger lines exist that no balanced journal could have produced.
     */
    public record CurrencyTotals(
            String currency,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal debitTotal,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal creditTotal,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal difference,
            boolean balanced,
            long lineCount
    ) {}
}
