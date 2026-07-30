package com.gme.pay.payment.opsrun;

/**
 * Who asked for a ledger-ops run (column {@code ledger_ops_runs.trigger_source}, Flyway V008).
 *
 * <p>Both triggers go through the same {@link LedgerOpsRunExecutor}, so an operator-triggered run is
 * recorded and alerted exactly like a scheduled one — there is no "manual runs are invisible" second class
 * (the same rule T3-4 established for settlement's batch re-runs).
 */
public enum LedgerOpsRunTrigger {

    /** The cron/fixed-delay scheduler. */
    SCHEDULER,

    /** An operator, via the internal ops API. {@code operator_id} is populated. */
    OPERATOR
}
