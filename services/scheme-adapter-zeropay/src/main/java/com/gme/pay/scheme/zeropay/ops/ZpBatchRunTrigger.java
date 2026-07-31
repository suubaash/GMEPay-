package com.gme.pay.scheme.zeropay.ops;

/** What caused a ZeroPay batch-window run — persisted on {@code zp_batch_runs.trigger_source}. */
public enum ZpBatchRunTrigger {

    /** One of the six {@code @Scheduled} KST windows fired. */
    SCHEDULER,

    /** An operator invoked the manual trigger ({@code operator_id} / {@code reason} are set). */
    OPERATOR_RERUN
}
