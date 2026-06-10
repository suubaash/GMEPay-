package com.gme.pay.bff.client;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Read-only view of settlement-reconciliation. Production calls
 * {@code GET /v1/settlements}; Phase-1 default is an in-memory stub.
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

    record SettlementBatchSummary(
            String batchId,
            String partnerId,
            LocalDate settlementDate,
            String currency,
            BigDecimal amount,
            String status
    ) {}
}
