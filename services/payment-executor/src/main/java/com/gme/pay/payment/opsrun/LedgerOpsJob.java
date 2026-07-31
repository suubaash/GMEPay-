package com.gme.pay.payment.opsrun;

/**
 * The ledger-ops jobs recorded in {@code ledger_ops_runs} (gap <b>T2-5</b>).
 *
 * <p>String constants rather than an enum because the values are also a DB CHECK constraint
 * ({@code ck_ledger_ops_runs_job}, Flyway V008) and a query parameter on the ops read surface; keeping one
 * spelling in one place is what stops the column and the code drifting.
 */
public final class LedgerOpsJob {

    /** Drains {@code revenue_posting_failures} back into revenue-ledger. */
    public static final String REVENUE_POSTING_REPLAY = "REVENUE_POSTING_REPLAY";

    /** Produces the persisted three-way day-close artifact for a business date. */
    public static final String DAY_CLOSE = "DAY_CLOSE";

    /** Derives the net open FX position per currency for a date range. */
    public static final String FX_EXPOSURE = "FX_EXPOSURE";

    /**
     * Operator-triggered move of POISON rows back to PENDING after the cause has been fixed (T2-5
     * follow-up, Flyway V012 widened {@code ck_ledger_ops_runs_job} for it).
     *
     * <p>Unlike the other three this is never scheduled — it exists only as an attributed human act, which
     * is exactly why it must land in the run ledger: the requeue resets {@code attempts} to 0 and therefore
     * discards the row's own record of how many times the posting had been pushed at the ledger. This row is
     * where that history survives.
     */
    public static final String REVENUE_POSTING_REQUEUE = "REVENUE_POSTING_REQUEUE";

    private LedgerOpsJob() {
    }
}
