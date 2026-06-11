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
