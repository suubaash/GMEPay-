package com.gme.pay.ledger.web;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The revenue-records ↔ journal-lines self-check for a period (T2-4 / CFO#4, CFO#7) — the report the
 * finance team runs to answer "is everything we recorded as revenue actually on the double-entry books,
 * and if not, what is missing and why".
 *
 * <p>Three kinds of finding, each deliberately explicit rather than silent:
 * <ol>
 *   <li><b>recorded-but-not-journalled</b> — a {@code revenue_records} / {@code commission_splits} row
 *       carrying money with no corresponding journal credit ({@code notJournalledTxnRefs}). Expected to
 *       be non-empty only for rows written before T2-4, since capture now records and journals in one
 *       transaction.</li>
 *   <li><b>tie-out variance</b> — the recorded total for a revenue stream vs the total actually credited
 *       to its income account for the same references ({@code tieOuts}). A non-zero {@code variance} on
 *       any stream means the two books disagree on AMOUNT even where both have rows.</li>
 *   <li><b>unmapped component</b> — money the module <em>records</em> but cannot journal because no
 *       account code exists for it, so booking it would require inventing an accounting policy
 *       ({@code unmappedComponents}). This is a finance-owner decision, surfaced with the exact amount
 *       at stake instead of being dropped.</li>
 * </ol>
 *
 * <p>{@code clean} is true only when there are no missing journals, no tie-out variance AND no unmapped
 * amount. While an unmapped component carries money, {@code clean} stays false by design: the books are
 * genuinely incomplete until the missing account code is decided, and this report must not claim
 * otherwise.
 *
 * @param startDate          inclusive first revenue date in scope
 * @param endDate            inclusive last revenue date in scope
 * @param revenueRecords     coverage of {@code revenue_records} (FX margin + service charge)
 * @param commissionSplits   coverage of {@code commission_splits} (scheme-side leg)
 * @param tieOuts            per-stream recorded-vs-journalled amount comparison
 * @param unmappedComponents recorded money that has no account code to be journalled against
 * @param clean              true iff every check above passed
 */
public record RevenueJournalReconciliationView(
        LocalDate startDate,
        LocalDate endDate,
        Coverage revenueRecords,
        Coverage commissionSplits,
        List<TieOut> tieOuts,
        List<UnmappedComponent> unmappedComponents,
        boolean clean
) {

    /**
     * How many of one record type's rows in the period reached the journal.
     *
     * @param source               the table this coverage is over
     * @param total                rows with a revenue date in range
     * @param journalled           rows carrying money that DO have a journal credit
     * @param notJournalled        rows carrying money that do NOT (the exceptions)
     * @param zeroAmount           rows carrying no money at all — nothing to journal, not an exception
     * @param notJournalledTxnRefs the exception references, capped (see {@code truncated})
     * @param truncated            true when more exceptions exist than are listed
     */
    public record Coverage(
            String source,
            long total,
            long journalled,
            long notJournalled,
            long zeroAmount,
            List<String> notJournalledTxnRefs,
            boolean truncated
    ) {}

    /**
     * One revenue stream's recorded total vs what the journal actually credited for the same references.
     *
     * @param stream          revenue stream name (e.g. {@code FX_MARGIN})
     * @param account         the income account the stream credits
     * @param currency        ISO-4217 currency of both amounts
     * @param recordedAmount  total per the record store (the subledger)
     * @param journalledAmount total CREDITs on {@code account} for those same references
     * @param variance        {@code recordedAmount − journalledAmount}; must be zero
     * @param tied            true iff {@code variance} is exactly zero
     */
    public record TieOut(
            String stream,
            String account,
            String currency,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal recordedAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal journalledAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal variance,
            boolean tied
    ) {}

    /**
     * Money that is recorded but cannot be journalled for want of an account code — an open decision,
     * reported with its size so the exposure is quantified.
     *
     * @param component        stable identifier for the unmapped money
     * @param source           the column it is recorded in
     * @param currency         ISO-4217 currency
     * @param amount           the period total that is NOT on the double-entry books
     * @param recordCount      how many rows contributed
     * @param reason           why it cannot be journalled today
     * @param decisionRequired what the finance owner has to decide before it can be
     */
    public record UnmappedComponent(
            String component,
            String source,
            String currency,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
            long recordCount,
            String reason,
            String decisionRequired
    ) {}
}
