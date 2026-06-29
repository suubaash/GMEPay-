package com.gme.pay.settlement.port;

import com.gme.pay.settlement.model.TransactionRecord;

import java.time.LocalDate;
import java.util.List;

/**
 * Anti-corruption port: interface for fetching transaction data from transaction-mgmt service.
 * The real implementation calls transaction-mgmt's REST API.
 * Tests use a stub/in-memory implementation — never WireMock from domain-layer unit tests.
 *
 * <p>MSA rule: settlement-reconciliation NEVER reads transaction-mgmt's database directly.
 */
public interface TransactionQueryPort {

    /**
     * Return all APPROVED transactions for the given settlement date that have not yet been
     * assigned to a settlement batch (settlement_batch_id IS NULL).
     */
    List<TransactionRecord> findUnbatchedApproved(LocalDate settlementDate);

    /**
     * Return all REFUNDED transactions for the given settlement date that have not yet been assigned
     * to a settlement batch. Refunds reduce the merchant's net payout: a refunded transaction's KRW
     * payout is clawed back, so the settlement engine deducts it (net = gross − fee − refund) and
     * reports {@code refund_count}/{@code refund_amount} in the ZP0061/0063 file.
     *
     * <p><b>Scope:</b> matches the same per-(creation-)date model as {@link #findUnbatchedApproved}
     * — a refund whose original transaction was created on {@code settlementDate}. A refund of a
     * prior-day payment (original created earlier) nets in the window of the original creation date,
     * not the refund date; cross-date refund netting is a follow-up tied to a refund-date filter.
     */
    List<TransactionRecord> findUnbatchedRefunded(LocalDate settlementDate);

    /**
     * Return all transactions assigned to the given batch id.
     */
    List<TransactionRecord> findByBatchId(Long batchId);
}
