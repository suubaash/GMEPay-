package com.gme.pay.payment.domain.client;

import com.gme.pay.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

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

    /**
     * Reads the amounts a refund must be validated and pro-rated against (gap T2-6):
     * {@code GET /v1/transactions/{txnRef}}.
     *
     * <p>Without this, {@code refundPayment} had nothing to check a requested amount against — which is
     * exactly why the refund path could only ever reverse the whole prefunding hold.
     *
     * <p>The default returns {@link Optional#empty()} ("basis unknown") so every hand-written test fake and
     * every existing caller stays valid. {@code empty} is NOT treated as permission: a FULL refund proceeds
     * unchanged (its amount needs no validation — it is whatever was captured), while a PARTIAL refund is
     * refused with {@code REFUND_BASIS_UNAVAILABLE}. Fail-closed for the case that needs the data.
     *
     * @param txnRef the internal transaction reference
     * @return the refund basis, or empty when the transaction cannot be read
     */
    default Optional<RefundBasis> findRefundBasis(String txnRef) {
        return Optional.empty();
    }

    // ---- value objects ----

    /**
     * The subset of a persisted transaction a refund needs (T2-6).
     *
     * @param txnRef             the transaction reference
     * @param status             current status name (e.g. {@code "APPROVED"}, {@code "REFUNDED"}), nullable
     * @param collectionAmount   the ORIGINAL amount charged to the partner — the refundable ceiling
     * @param collectionCurrency ISO currency of {@code collectionAmount}
     * @param prefundDeductedUsd the USD taken from the partner float for this payment at the ORIGINAL locked
     *                           rate. Pro-rating this is what makes a partial refund a locked-rate reversal
     *                           without re-quoting: no rate is read, so no rate can have moved.
     * @param refundedAmount     the amount already refunded (cumulative), in {@code collectionCurrency};
     *                           null / absent means nothing has been refunded yet
     */
    record RefundBasis(
            String txnRef,
            String status,
            BigDecimal collectionAmount,
            String collectionCurrency,
            BigDecimal prefundDeductedUsd,
            BigDecimal refundedAmount
    ) {
        /** Cumulative amount already refunded, never null. */
        public BigDecimal alreadyRefunded() {
            return refundedAmount == null ? BigDecimal.ZERO : refundedAmount.abs();
        }

        /** The amount still refundable, or null when the original amount is unknown. */
        public BigDecimal refundableRemaining() {
            return collectionAmount == null ? null : collectionAmount.subtract(alreadyRefunded());
        }
    }

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
            BigDecimal payoutMarginUsd,
            /**
             * T4-4: the merchant DISPLAY NAME this corridor resolved at payment time, carried so
             * transaction-mgmt PERSISTS it (V012 {@code transactions.merchant_name}). Until this field
             * existed the name lived only on the synchronous wallet response, so every later receipt /
             * transaction-detail read rendered an em dash even though the corridor had known the name
             * seconds earlier.
             *
             * <p>Nullable, and null is the correct value whenever the name is genuinely unknown (the
             * CPM path has no QR decode; the failover path has no merchant lookup). Populate it via
             * {@link com.gme.pay.payment.domain.MerchantNames#realOrNull} so a lenient-mode
             * {@code "Unknown Merchant"} placeholder or the merchant id can never be stored as if it
             * were a real name.
             */
            String merchantName
    ) {
        /** Backwards-compatible 12-arg constructor; rate-lock pool fields default null. */
        public CreateRequest(
                long partnerId, String partnerTxnRef, String schemeId, String direction,
                String paymentMode, BigDecimal targetPayout, String payoutCurrency,
                BigDecimal collectionAmount, String collectionCurrency, String merchantId,
                String quoteId, BigDecimal merchantFeeRate) {
            this(partnerId, partnerTxnRef, schemeId, direction, paymentMode, targetPayout,
                    payoutCurrency, collectionAmount, collectionCurrency, merchantId, quoteId,
                    merchantFeeRate, null, null, null, null, null, null, null, null, null);
        }

        /**
         * T4-4 13-arg form: the pre-Wave-3 shape PLUS the merchant name. For corridors that carry no
         * rate-lock pool (the wallet paths) but DO know who the merchant is.
         */
        public CreateRequest(
                long partnerId, String partnerTxnRef, String schemeId, String direction,
                String paymentMode, BigDecimal targetPayout, String payoutCurrency,
                BigDecimal collectionAmount, String collectionCurrency, String merchantId,
                String quoteId, BigDecimal merchantFeeRate, String merchantName) {
            this(partnerId, partnerTxnRef, schemeId, direction, paymentMode, targetPayout,
                    payoutCurrency, collectionAmount, collectionCurrency, merchantId, quoteId,
                    merchantFeeRate, null, null, null, null, null, null, null, null, merchantName);
        }

        /** Back-compat 20-arg Wave-3 constructor; {@code merchantName} defaults null. */
        public CreateRequest(
                long partnerId, String partnerTxnRef, String schemeId, String direction,
                String paymentMode, BigDecimal targetPayout, String payoutCurrency,
                BigDecimal collectionAmount, String collectionCurrency, String merchantId,
                String quoteId, BigDecimal merchantFeeRate,
                BigDecimal offerRateColl, BigDecimal crossRate,
                BigDecimal costRateColl, BigDecimal costRatePay,
                BigDecimal collectionUsd, BigDecimal payoutUsdCost,
                BigDecimal collectionMarginUsd, BigDecimal payoutMarginUsd) {
            this(partnerId, partnerTxnRef, schemeId, direction, paymentMode, targetPayout,
                    payoutCurrency, collectionAmount, collectionCurrency, merchantId, quoteId,
                    merchantFeeRate, offerRateColl, crossRate, costRateColl, costRatePay,
                    collectionUsd, payoutUsdCost, collectionMarginUsd, payoutMarginUsd, null);
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
            BigDecimal costRatePay,
            /**
             * T2-6: CUMULATIVE KRW refunded for this transaction, carried on the REFUNDED commit.
             * transaction-mgmt persists it to {@code transactions.refund_amount_krw}, which is what
             * settlement's cross-date claw-back nets on. Before this field the refund path re-sent the
             * row's existing (always null) value, so the claw-back magnitude was always zero and nothing
             * was ever netted back. Null on every non-refund patch and null-skipped downstream.
             */
            BigDecimal refundAmountKrw
    ) {
        /** Full 13-arg pre-T2-6 form; {@code refundAmountKrw} defaults null. */
        public StatusPatch(
                PaymentStatus newStatus,
                String schemeTxnRef,
                String schemeApprovalCode,
                BigDecimal prefundDeductedUsd,
                Instant approvedAt,
                BigDecimal bookedSettlementAmount,
                String settlementRoundingMode,
                BigDecimal roundingResidual,
                BigDecimal collectionMarginUsd,
                BigDecimal payoutMarginUsd,
                BigDecimal collectionUsd,
                BigDecimal costRateColl,
                BigDecimal costRatePay) {
            this(newStatus, schemeTxnRef, schemeApprovalCode, prefundDeductedUsd, approvedAt,
                    bookedSettlementAmount, settlementRoundingMode, roundingResidual,
                    collectionMarginUsd, payoutMarginUsd, collectionUsd, costRateColl, costRatePay, null);
        }

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
                    null, null, null, null, null, null);
        }

        /**
         * T2-6 refund commit: status + scheme refs + the USD credited back + the cumulative refunded KRW.
         * Deliberately does NOT carry {@code approvedAt} / rounding-lock / margin fields, so a refund never
         * has to restate the original commit's values.
         */
        public static StatusPatch refund(PaymentStatus newStatus,
                                         String schemeTxnRef,
                                         String schemeApprovalCode,
                                         BigDecimal reversedUsd,
                                         BigDecimal cumulativeRefundAmountKrw) {
            return new StatusPatch(newStatus, schemeTxnRef, schemeApprovalCode, reversedUsd, null,
                    null, null, null, null, null, null, null, null, cumulativeRefundAmountKrw);
        }
    }
}
