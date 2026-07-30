package com.gme.pay.payment.dayclose;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The three-way day-close artifact for one business date (gap <b>T2-5</b> / CFO#10: "no three-way daily finance
 * reconciliation (scheme ↔ ledger ↔ prefunding) or day-close report").
 *
 * <h2>The three legs</h2>
 * <ol>
 *   <li><b>Transactions</b> — transaction-mgmt's APPROVED rows for the date: what we told the customer and the
 *       merchant happened, per corridor and per currency.</li>
 *   <li><b>Ledger journal</b> — QUOTED from revenue-ledger's own two reports
 *       ({@code GET /v1/journals/trial-balance} and {@code GET /v1/revenue/journal-reconciliation}), never
 *       recomputed. Recomputing the ledger's arithmetic outside the ledger would let the two drift, and the
 *       drift would surface here as a variance that does not exist.</li>
 *   <li><b>Prefunding movements</b> — the signed float movements per partner, from the date-ranged endpoint
 *       T2-8 added, so a deduct-and-reverse pair nets to zero instead of looking like consumed float.</li>
 * </ol>
 *
 * <h2>It reports facts and names variances. It does not decide accounting.</h2>
 * <p>Every {@link Variance} is a difference between two figures that both exist, labelled with where each came
 * from. Nothing is netted, absorbed, adjusted or explained away — in particular:
 *
 * <ul>
 *   <li>A variance whose TREATMENT is an open finance decision carries
 *       {@link Variance.Treatment#UNRESOLVED_DECISION} and appears again in {@link #unresolvedDecisions()} with
 *       the register item and the actual question. Today those are <b>T2-10</b> (which account the partner-side
 *       commission carve hits, so {@code REVENUE_GME_FEE_SHARE} currently overstates retained commission) and
 *       <b>T2-11</b> (how captured revenue is backed out on a refund, especially a partial one, and the double
 *       relief of {@code RECEIVABLE_PARTNER}). Both are reported as UNRESOLVED, with their amounts, for as long
 *       as they carry money. Neither is netted away and neither is guessed at.</li>
 *   <li>A leg that could not be read is {@code available=false} with a reason. It is never rendered as zero —
 *       "we could not look" and "nothing happened" must not print identically.</li>
 * </ul>
 *
 * <p>{@link #clean()} is true only when there are no variances, no unresolved decisions carrying money, and no
 * unavailable leg. A day that is arithmetically tidy but whose treatment is undecided is not a closed day.
 *
 * @param businessDate        the date closed, in {@link #closeZone}
 * @param generatedAt         when this version of the report was produced
 * @param closeZone           the timezone the business date was interpreted in
 * @param clean               see above — the single flag a day-close check should assert on
 * @param legs                availability of each of the three legs
 * @param corridors           per-corridor transaction totals
 * @param currencies          per-currency transaction totals set against the journal's totals for that currency
 * @param ledger              revenue-ledger's own verdicts, quoted
 * @param prefunding          per-partner float movement set against the USD the transactions say was deducted
 * @param postingBacklog      revenue postings that never reached the ledger (the T2-5 replay queue) — the direct
 *                            answer to CFO#6's "a one-sided failure is invisible to any current report"
 * @param fxExposure          the same date's derived FX position, embedded so the persisted artifact carries it
 * @param variances           every named difference
 * @param unresolvedDecisions the subset whose treatment is a finance-owner decision, with the question
 */
public record DayCloseReport(
        LocalDate businessDate,
        Instant generatedAt,
        String closeZone,
        boolean clean,
        List<Leg> legs,
        List<CorridorSummary> corridors,
        List<CurrencySummary> currencies,
        LedgerSummary ledger,
        List<PrefundingSummary> prefunding,
        PostingBacklog postingBacklog,
        FxExposureReport fxExposure,
        List<Variance> variances,
        List<UnresolvedDecision> unresolvedDecisions
) {

    /** Leg names, stable so a monitor can key on them. */
    public static final String LEG_TRANSACTIONS = "TRANSACTIONS";
    public static final String LEG_LEDGER_JOURNAL = "LEDGER_JOURNAL";
    public static final String LEG_PREFUNDING = "PREFUNDING";

    /**
     * Whether one leg could be read.
     *
     * @param leg       one of the {@code LEG_*} constants
     * @param available false ⇒ the figures for this leg are ABSENT, not zero
     * @param source    what was read (endpoint / partner list), for an auditor reading the artifact alone
     * @param reason    why it is unavailable; null when available
     */
    public record Leg(String leg, boolean available, String source, String reason) {

        public static Leg ok(String leg, String source) {
            return new Leg(leg, true, source, null);
        }

        public static Leg unavailable(String leg, String source, String reason) {
            return new Leg(leg, false, source, reason);
        }
    }

    /**
     * One corridor's transaction totals. The corridor is {@code schemeId collectionCcy->payoutCcy}, derived from
     * the transactions themselves rather than from a configured corridor list — a corridor that starts carrying
     * traffic appears in the close without anyone remembering to register it.
     */
    public record CorridorSummary(
            String corridor,
            String schemeId,
            String collectionCcy,
            String payoutCcy,
            int txnCount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal collectedAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal payoutAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal refundedAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal prefundDeductedUsd,
            int missingPrefundUsdCount
    ) {}

    /**
     * One currency, transactions beside the journal.
     *
     * <p>The two sides are NOT expected to be equal and no variance is raised from their difference: transaction
     * amounts are gross customer money, journal amounts are GME's revenue in that currency. They are presented
     * together because that is the comparison a finance reader makes by eye, and because a currency with
     * transactions and NO journal movement at all is the shape of a dropped posting.
     *
     * @param journalLineCount 0 with a non-zero {@code txnCount} is the signal worth looking at
     */
    public record CurrencySummary(
            String currency,
            int collectedTxnCount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal collectedAmount,
            int paidOutTxnCount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal paidOutAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal journalDebitTotal,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal journalCreditTotal,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal journalDifference,
            boolean journalBalanced,
            long journalLineCount
    ) {}

    /**
     * revenue-ledger's own verdicts on the date, quoted rather than recomputed.
     *
     * @param trialBalanceBalanced      revenue-ledger's {@code balanced} flag
     * @param journalReconciliationClean revenue-ledger's {@code clean} flag (false while ANY unmapped amount
     *                                  carries money — this report does not override it)
     * @param recordsNotJournalled      revenue records carrying money with no journal credit
     * @param splitsNotJournalled       commission splits carrying money with no journal credit
     * @param untiedStreams             revenue streams whose recorded and journalled totals disagree
     */
    public record LedgerSummary(
            boolean trialBalanceBalanced,
            boolean journalReconciliationClean,
            long recordsNotJournalled,
            long splitsNotJournalled,
            List<String> untiedStreams
    ) {}

    /**
     * One partner's float movement beside the USD its transactions say was deducted.
     *
     * <p>This is the tie-out that catches a one-sided failure: the transaction says USD left the float, the
     * float says it did not (or vice versa). {@code floatNetUsd} is SIGNED as prefunding reports it (negative
     * consumes float), so {@code floatConsumedUsd} is its negation and is what compares against the
     * transactions.
     *
     * @param delta {@code txnDeductedUsd − floatConsumedUsd}; zero is the healthy answer
     */
    public record PrefundingSummary(
            String partnerCode,
            int movementCount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal floatNetUsd,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal floatConsumedUsd,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal txnDeductedUsd,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal delta
    ) {}

    /**
     * Revenue postings that never reached revenue-ledger, from payment-executor's own
     * {@code revenue_posting_failures} table.
     *
     * <p>On the report because CFO#6 and CFO#10 are the same wound seen from two sides: a capture that succeeded
     * while its revenue posting was dropped is invisible to a ledger-only report — the ledger simply has less in
     * it and balances perfectly. The backlog count is the only place that discrepancy is visible.
     */
    public record PostingBacklog(
            long outstanding,
            long pending,
            long poison,
            Instant oldestOutstandingAt
    ) {}

    /**
     * One named difference between two figures that both exist.
     *
     * @param code        stable identifier, so a monitor can alert on a specific variance
     * @param dimension   what it is scoped to (partner code / currency / stream / global)
     * @param currency    ISO-4217 currency of the amounts, or null when the variance is a count
     * @param leftLabel   where {@code leftAmount} came from
     * @param leftAmount  the first figure
     * @param rightLabel  where {@code rightAmount} came from
     * @param rightAmount the second figure
     * @param delta       {@code leftAmount − rightAmount}
     * @param treatment   whether this is a defect to investigate or an open decision
     * @param note        what the reader should understand about it — never an explanation that dismisses it
     */
    public record Variance(
            String code,
            String dimension,
            String currency,
            String leftLabel,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal leftAmount,
            String rightLabel,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal rightAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal delta,
            Treatment treatment,
            String note
    ) {

        /** How the reader should act on a variance. */
        public enum Treatment {

            /** Something is wrong and someone should find out what. */
            DEFECT_TO_INVESTIGATE,

            /**
             * The amount is not in dispute; how it should be BOOKED is an open finance-owner decision. Reported
             * every period, never netted away, and {@code clean} stays false while it carries money.
             */
            UNRESOLVED_DECISION
        }
    }

    /**
     * An open finance-owner decision that this date's numbers depend on.
     *
     * @param registerItem     the gap-register id (e.g. {@code T2-10})
     * @param component        stable identifier for the money involved
     * @param currency         ISO-4217 currency of {@code amount}
     * @param amount           how much money the undecided treatment applies to on this date
     * @param question         what has to be decided — quoted from the register, not paraphrased into a guess
     * @param effectIfUndecided what the reader should assume is WRONG in the meantime
     */
    public record UnresolvedDecision(
            String registerItem,
            String component,
            String currency,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
            String question,
            String effectIfUndecided
    ) {}
}
