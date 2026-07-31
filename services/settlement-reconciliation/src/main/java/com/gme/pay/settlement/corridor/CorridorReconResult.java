package com.gme.pay.settlement.corridor;

import com.gme.pay.settlement.persistence.CorridorReconSummaryEntity;

import java.time.LocalDate;
import java.util.List;

/**
 * Outcome of one {@link CorridorThreeWayReconciler} run: every classified line (MATCHED included, for
 * the audit trail) plus the persisted day summary finance reads.
 *
 * @param batchId        recon run id, also the {@code recon_exceptions.batch_id} of its breaks
 * @param settlementDate business date reconciled
 * @param scheme         upper-case scheme code
 * @param lines          one line per transaction reference seen on any of the three legs
 * @param summary        the persisted {@code corridor_recon_summary} row for the date
 */
public record CorridorReconResult(
        String batchId,
        LocalDate settlementDate,
        String scheme,
        List<ThreeWayLine> lines,
        CorridorReconSummaryEntity summary) {

    /** Lines needing ops/finance attention. */
    public List<ThreeWayLine> breaks() {
        return lines.stream().filter(ThreeWayLine::requiresAttention).toList();
    }
}
