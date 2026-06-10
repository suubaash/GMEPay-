package com.gme.pay.bff.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Read-only view of transaction-mgmt. Production implementation calls
 * {@code GET /v1/transactions/{id}} and a list endpoint; the Phase-1 default is
 * an in-memory stub.
 */
public interface TransactionMgmtClient {

    /** Fetch a single transaction by id; returns {@code null} when unknown. */
    TransactionSummary getTransaction(String txnId);

    /**
     * Fetch the {@code limit} most recent transactions across all partners
     * (Admin UI) or for one partner (Portal UI).
     *
     * @param partnerId optional — null means all partners
     * @param limit     maximum number of rows to return
     */
    List<TransactionSummary> recent(String partnerId, int limit);

    record TransactionSummary(
            String txnId,
            String partnerId,
            String state,
            BigDecimal amount,
            String currency,
            Instant committedAt
    ) {}
}
