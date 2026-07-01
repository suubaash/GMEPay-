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
            String quoteId,
            /** V032/V005: gross merchant fee rate resolved at creation; nullable. */
            BigDecimal merchantFeeRate,
            // Wave-3 rate-lock pool fields (additive, IR-txn-2): sourced from the locked quote so
            // transaction-mgmt can persist real margins (fixes FX1015 zero-margin). Nullable.
            BigDecimal offerRateColl,
            BigDecimal crossRate,
            BigDecimal costRateColl,
            BigDecimal costRatePay,
            BigDecimal collectionUsd,
            BigDecimal payoutUsdCost,
            BigDecimal collectionMarginUsd,
            BigDecimal payoutMarginUsd
    ) {
        /** Backwards-compatible 12-arg constructor; rate-lock pool fields default null. */
        public CreateRequest(
                long partnerId, String partnerTxnRef, String schemeId, String direction,
                String paymentMode, BigDecimal targetPayout, String payoutCurrency,
                BigDecimal collectionAmount, String collectionCurrency, String merchantId,
                String quoteId, BigDecimal merchantFeeRate) {
            this(partnerId, partnerTxnRef, schemeId, direction, paymentMode, targetPayout,
                    payoutCurrency, collectionAmount, collectionCurrency, merchantId, quoteId,
                    merchantFeeRate, null, null, null, null, null, null, null, null);
        }
    }

    record CreateResult(String txnRef, String paymentId, Instant createdAt) {}

    record StatusPatch(
            PaymentStatus newStatus,
            String schemeTxnRef,
            String schemeApprovalCode,
            BigDecimal prefundDeductedUsd,
            Instant approvedAt,
            BigDecimal bookedSettlementAmount,
            String settlementRoundingMode,
            BigDecimal roundingResidual,
            // Wave-3 commit-margin fields (additive, FX1015 accuracy): carried from the locked quote
            // on the APPROVED commit so transaction-mgmt persists real margins. Nullable.
            BigDecimal collectionMarginUsd,
            BigDecimal payoutMarginUsd,
            BigDecimal collectionUsd,
            BigDecimal costRateColl,
            BigDecimal costRatePay
    ) {
        /**
         * Backwards-compatible 5-arg constructor used by FAILED / UNCERTAIN / REVERSED branches
         * that do not need to carry the per-partner rounding lock. Equivalent to the 8-arg form
         * with the rounding + margin fields set to {@code null}.
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

        /** Backwards-compatible 8-arg constructor; commit-margin fields default null. */
        public StatusPatch(
                PaymentStatus newStatus,
                String schemeTxnRef,
                String schemeApprovalCode,
                BigDecimal prefundDeductedUsd,
                Instant approvedAt,
                BigDecimal bookedSettlementAmount,
                String settlementRoundingMode,
                BigDecimal roundingResidual) {
            this(newStatus, schemeTxnRef, schemeApprovalCode, prefundDeductedUsd, approvedAt,
                    bookedSettlementAmount, settlementRoundingMode, roundingResidual,
                    null, null, null, null, null);
        }
    }
}
