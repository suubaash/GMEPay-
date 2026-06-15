package com.gme.pay.settlement.exception;

/**
 * Lifecycle status of a {@code recon_exception} row as managed by ops.
 *
 * <p>Transitions:
 * <pre>
 *   OPEN  -->  RESOLVED   (ops confirms the discrepancy is explained / corrected)
 *   OPEN  -->  RE_RUN     (ops requests automatic recon re-attempt)
 *   RE_RUN --> OPEN       (after re-run: stays open if still unmatched)
 *   RE_RUN --> RESOLVED   (after re-run: auto-resolved if now matched)
 * </pre>
 */
public enum ExceptionStatus {
    /** Created by the diff engine; awaiting ops action. */
    OPEN,
    /** Ops confirmed resolution (manual or auto post-re-run). */
    RESOLVED,
    /** Ops requested a re-run of the diff for this exception's batch window. */
    RE_RUN
}
