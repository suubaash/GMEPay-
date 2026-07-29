package com.gme.pay.settlement.runlog;

/**
 * How one batch-window run ended. Persisted as a VARCHAR on {@code batch_runs.outcome} (kept a String for
 * H2/PostgreSQL portability, same discipline as {@code settlement_batches.status}).
 */
public enum BatchRunOutcome {

    /** The window ran and produced (or idempotently re-found) its batch. */
    SUCCESS,

    /** The window threw. {@code failure_class} / {@code failure_message} / {@code failure_trace} say why. */
    FAILED,

    /** The configured business-day calendar declared the date a non-business date — deliberately not run. */
    SKIPPED_NON_BUSINESS_DAY,

    /** The feature gate ({@code gmepay.settlement.generation.enabled} / {@code ….recon.enabled}) was off. */
    SKIPPED_DISABLED;

    /** Only a FAILED run is an incident; the two SKIPPED values are the system working as configured. */
    public boolean isFailure() {
        return this == FAILED;
    }
}
