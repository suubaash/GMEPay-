package com.gme.pay.txn.api.dto;

import com.gme.pay.txn.domain.model.Transaction;

import java.time.Instant;

/**
 * Slim response for POST /v1/transactions.
 *
 * <p>Field names match payment-executor's {@code TransactionCreatedResponse} EXACTLY:
 * <ul>
 *   <li>{@code txnRef}     – transaction-mgmt's internal reference (UUID)</li>
 *   <li>{@code paymentId}  – payment-executor's opaque payment ID (UUID, generated on create)</li>
 *   <li>{@code createdAt}  – UTC instant the transaction was created</li>
 * </ul>
 *
 * <p>Consumers: payment-executor RestTransactionClient.TransactionCreatedResponse reads exactly
 * these three fields by name. Do NOT rename them.
 */
public record CreateTransactionResponse(
        String txnRef,
        String paymentId,
        Instant createdAt
) {
    public static CreateTransactionResponse from(Transaction txn) {
        return new CreateTransactionResponse(
                txn.txnRef(),
                txn.paymentId(),
                txn.createdAt());
    }
}
