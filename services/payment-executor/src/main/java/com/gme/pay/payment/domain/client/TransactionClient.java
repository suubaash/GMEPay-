package com.gme.pay.payment.domain.client;

import com.gme.pay.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Interface to the Transaction Management service.
 * Implementations call transaction-mgmt's REST API; tests use hand-written fakes.
 */
public interface TransactionClient {

    /**
     * Creates a new transaction record in PENDING state.
     *
     * @param request the create-transaction request
     * @return the persisted transaction reference
     */
    CreateResult createPending(CreateRequest request);

    /**
     * Transitions an existing transaction to the given status and commits all rate-lock fields.
     *
     * @param txnRef the internal transaction reference
     * @param patch  the fields to commit
     */
    void commitStatus(String txnRef, StatusPatch patch);

    // ---- value objects ----

    record CreateRequest(
            long partnerId,
            String partnerTxnRef,
            String schemeId,
            String direction,
            String paymentMode,
            BigDecimal targetPayout,
            String payoutCurrency,
            BigDecimal collectionAmount,
            String collectionCurrency,
            String merchantId,
            String quoteId
    ) {}

    record CreateResult(String txnRef, String paymentId, Instant createdAt) {}

    record StatusPatch(
            PaymentStatus newStatus,
            String schemeTxnRef,
            String schemeApprovalCode,
            BigDecimal prefundDeductedUsd,
            Instant approvedAt,
            BigDecimal bookedSettlementAmount,
            String settlementRoundingMode,
            BigDecimal roundingResidual
    ) {
        /**
         * Backwards-compatible 5-arg constructor used by FAILED / UNCERTAIN / REVERSED branches
         * that do not need to carry the per-partner rounding lock. Equivalent to the 8-arg form
         * with the three rounding fields set to {@code null}.
         */
        public StatusPatch(
                PaymentStatus newStatus,
                String schemeTxnRef,
                String schemeApprovalCode,
                BigDecimal prefundDeductedUsd,
                Instant approvedAt) {
            this(newStatus, schemeTxnRef, schemeApprovalCode, prefundDeductedUsd, approvedAt,
                    null, null, null);
        }
    }
}
