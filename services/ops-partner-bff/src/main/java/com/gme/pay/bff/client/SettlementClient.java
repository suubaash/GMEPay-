package com.gme.pay.bff.client;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Read-only view of settlement-reconciliation. Production calls
 * {@code GET /v1/settlements}; Phase-1 default is an in-memory stub.
 *
 * <p>Phase C2 adds a per-batch detail accessor so the Admin UI settlement-batch
 * drawer can show the matched/unmatched lines.
 */
public interface SettlementClient {

    /**
     * Returns the most recent settlement batches across all partners
     * (Admin UI) or for one partner (Portal UI).
     *
     * @param partnerId optional — null means all partners
     * @param limit     maximum number of batches to return
     */
    List<SettlementBatchSummary> recent(String partnerId, int limit);

    /**
     * Fetches one settlement batch and its lines. Returns {@code null} when the
     * batch is unknown — the controller maps that to HTTP 404.
     */
    SettlementBatchDetail detail(String batchId);

    // -------- Ops wave: recon exceptions gauge + operator rerun --------------
    //
    // Additive default methods (never break existing stubs / fakes). Both real
    // implementations (rest + stub) override.

    /**
     * Count of OPEN reconciliation exceptions (unmatched / breaks) for the
     * control-tower gauge. Routes to settlement-reconciliation's exceptions
     * endpoint. Never throws — degrades to {@code null} ("unknown") when the
     * upstream is unavailable so the control-tower section shows unknown, not 500.
     */
    default Integer openReconExceptions() {
        return null;
    }

    /**
     * Operator-triggered reconciliation rerun. Routes to settlement-reconciliation's
     * {@code POST /v1/settlements/recon/rerun} carrying an optional {@code date} +
     * operator {@code reason}. Returns the outcome; upstream 4xx propagates as
     * {@code ResponseStatusException} from the rest impl.
     */
    default ReconRerunResult rerunRecon(String date, String actor, String reason) {
        throw new UnsupportedOperationException(
                "rerunRecon is not implemented by " + getClass().getName());
    }

    /** Outcome of a reconciliation rerun. */
    record ReconRerunResult(String status, Integer matched, Integer unmatched, String detail) {}

    record SettlementBatchSummary(
            String batchId,
            String partnerId,
            LocalDate settlementDate,
            String currency,
            BigDecimal amount,
            String status
    ) {}

    /** One row of a settlement batch — a single scheme/transaction line. */
    record SettlementLine(
            String txnRef,
            BigDecimal amount,
            String currency,
            boolean matched
    ) {}

    /** Full settlement-batch view used by the Admin UI drawer. */
    record SettlementBatchDetail(
            SettlementBatchSummary batch,
            List<SettlementLine> lines
    ) {}
}
