package com.gme.pay.scheme.zeropay.ops;

/**
 * How one ZeroPay batch-window run ended. Persisted as a VARCHAR on {@code zp_batch_runs.outcome} (kept a
 * String for H2/PostgreSQL portability). Mirrors settlement-reconciliation's {@code BatchRunOutcome}.
 */
public enum ZpBatchRunOutcome {

    /** The window generated (and, where applicable, transferred) its file. */
    SUCCESS,

    /** The window threw. {@code failure_class} / {@code failure_message} / {@code failure_trace} say why. */
    FAILED,

    /** The configured business-day calendar declared the date a non-business date — deliberately not run. */
    SKIPPED_NON_BUSINESS_DAY,

    /** {@code adapter.zeropay.batch-enabled=false} — the scheduler is switched off. */
    SKIPPED_DISABLED;

    /** Only FAILED is an incident; the two SKIPPED values are the system working as configured. */
    public boolean isFailure() {
        return this == FAILED;
    }
}
