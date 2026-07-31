package com.gme.pay.payment.opsrun;

/**
 * What one ledger-ops run did (column {@code ledger_ops_runs.outcome}, Flyway V008).
 *
 * <p>Mirrors settlement-reconciliation's {@code BatchRunOutcome} minus the calendar case: payment-executor
 * has no business-day calendar and these jobs are calendar-independent by design (money moves on banking
 * holidays, so a missing revenue posting still has to be replayed and a holiday still has to be closed).
 * See {@code V008__create_ledger_ops_runs.sql} for why that difference is stated rather than faked.
 */
public enum LedgerOpsRunOutcome {

    /** The work ran to completion. Says nothing about whether the work FOUND problems. */
    SUCCESS,

    /** The work threw. The failure is on the row and an {@code ops.alert} was raised. */
    FAILED,

    /** A feature gate was off, so nothing ran. Not an incident, and not alerted. */
    SKIPPED_DISABLED
}
