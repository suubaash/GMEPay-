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
     * Return all transactions assigned to the given batch id.
     */
    List<TransactionRecord> findByBatchId(Long batchId);
}
