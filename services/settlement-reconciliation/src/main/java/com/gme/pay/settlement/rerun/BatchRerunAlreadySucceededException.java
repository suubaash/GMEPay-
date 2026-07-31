package com.gme.pay.settlement.rerun;

import java.time.LocalDate;

/**
 * Thrown when an operator asks to re-run a settlement window that has <b>already completed
 * successfully</b> for that business date and did not pass {@code force} (gap <b>T3-4</b>: "safe to invoke
 * twice — refusing rather than duplicating if a successful run already exists").
 *
 * <p>Maps to HTTP <b>409 Conflict</b>: the request was well-formed, the state says no.
 *
 * <p>Refusal is the default rather than a silent no-op on purpose. A silent success would tell the operator
 * "done" for two materially different situations — "I regenerated it" and "I did nothing" — and after a
 * settlement file has been transmitted, regenerating it is a money-affecting act that must be a deliberate,
 * separately-recorded decision. The 409 names the run that already succeeded so the operator can look at it
 * before deciding whether {@code force} is really what they want.
 */
public class BatchRerunAlreadySucceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BatchRerunAlreadySucceededException(String fileType, String window, LocalDate date,
                                              Long existingRunId, String batchId) {
        super("refusing to re-run " + fileType + "/" + window + " for " + date
                + ": a successful run already exists (batch_runs.id=" + existingRunId
                + ", batchId=" + batchId + "). Inspect it first; pass force=true to regenerate anyway.");
    }
}
