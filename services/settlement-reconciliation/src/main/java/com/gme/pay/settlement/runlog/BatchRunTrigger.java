package com.gme.pay.settlement.runlog;

/** What caused a batch-window run — persisted on {@code batch_runs.trigger_source}. */
public enum BatchRunTrigger {

    /** A {@code @Scheduled} KST window fired. */
    SCHEDULER,

    /** An operator invoked the re-run API (the {@code operator_id} / {@code reason} columns are set). */
    OPERATOR_RERUN
}
