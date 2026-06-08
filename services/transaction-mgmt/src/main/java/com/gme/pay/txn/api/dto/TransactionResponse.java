package com.gme.pay.txn.api.dto;

import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response DTO for GET /v1/transactions/{txnRef} and state-transition endpoints.
 * Defined in this module – not a shared lib type.
 */
public record TransactionResponse(
        String txnRef,
        String partnerRef,
        BigDecimal sendAmount,
        String sendCcy,
        BigDecimal targetPayout,
        String targetCcy,
        TransactionStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    /** Map from domain aggregate. */
    public static TransactionResponse from(Transaction txn) {
        return new TransactionResponse(
                txn.txnRef(),
                txn.partnerRef(),
                txn.sendAmount(),
                txn.sendCcy(),
                txn.targetPayout(),
                txn.targetCcy(),
                txn.status(),
                txn.createdAt(),
                txn.updatedAt());
    }
}
