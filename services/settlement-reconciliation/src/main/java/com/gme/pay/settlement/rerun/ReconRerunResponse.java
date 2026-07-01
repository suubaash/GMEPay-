package com.gme.pay.settlement.rerun;

import java.util.List;

/**
 * Result of an operator recon re-run: the per-batch match/exception summary produced by the (idempotent)
 * diff engine. {@code totalMatched}/{@code totalExceptions} aggregate across every batch that was re-run.
 */
public record ReconRerunResponse(
        String operatorId,
        int batchesRerun,
        int totalMatched,
        int totalExceptions,
        List<BatchReconSummary> batches) {

    /** Per-batch match/exception counts after the re-run, plus the resulting batch status. */
    public record BatchReconSummary(
            String batchId,
            String status,
            int matched,
            int exceptions) {
    }
}
