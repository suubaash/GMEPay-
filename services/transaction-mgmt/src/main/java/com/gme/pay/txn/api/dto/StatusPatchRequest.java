package com.gme.pay.txn.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Request body for PATCH /v1/transactions/{ref}/status.
 *
 * <p>Field names match payment-executor's {@code StatusPatchRequest} EXACTLY
 * (Jackson binds by name — any mismatch silently produces null).
 *
 * <p>Canonical contract (payment-executor RestTransactionClient):
 * <ul>
 *   <li>{@code newStatus}               – String: target TransactionStatus name (e.g. "APPROVED")</li>
 *   <li>{@code schemeTxnRef}            – String: scheme's own transaction reference (nullable)</li>
 *   <li>{@code schemeApprovalCode}      – String: scheme approval/authorisation code (nullable)</li>
 *   <li>{@code prefundDeductedUsd}      – BigDecimal: USD deducted from prefunding balance (nullable)</li>
 *   <li>{@code approvedAt}              – Instant: scheme approval timestamp (nullable)</li>
 *   <li>{@code bookedSettlementAmount}  – BigDecimal: settlement liability at partner rounding (nullable)</li>
 *   <li>{@code settlementRoundingMode}  – String: RoundingMode name e.g. "DOWN" (nullable)</li>
 *   <li>{@code roundingResidual}        – BigDecimal: precise - booked (nullable)</li>
 * </ul>
 *
 * <h2>Wave-3 commit-margin fields (additive, FX1015 accuracy)</h2>
 *
 * <p>Appended so payment-executor can carry the rate-lock pool's margins/cost
 * rates on the commit, letting transaction-mgmt persist a margin-accurate
 * {@code offerRateColl} instead of deriving it with ZERO margin. All nullable;
 * existing callers that omit them leave the margin-derived fields empty (current
 * behaviour). Money/rate values ride as decimal strings.
 * <ul>
 *   <li>{@code collectionMarginUsd}     – USD margin on the collection leg (nullable)</li>
 *   <li>{@code payoutMarginUsd}         – USD margin on the payout leg (nullable)</li>
 *   <li>{@code collectionUsd}           – collection-leg USD amount (send_usd_cost base) (nullable)</li>
 *   <li>{@code costRateColl}            – collection-leg cost rate (market±buffer) (nullable)</li>
 *   <li>{@code costRatePay}             – payout-leg cost rate (nullable)</li>
 * </ul>
 *
 * <p>Note: payment-executor sends {@code newStatus} as {@code PaymentStatus} enum (PENDING,
 * APPROVED, FAILED, UNCERTAIN, CANCELLED, REVERSED, REFUNDED). We accept as String and
 * map to {@link com.gme.pay.txn.domain.model.TransactionStatus} in the service layer so
 * this module does not depend on payment-executor's enum.
 */
public record StatusPatchRequest(
        String newStatus,
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
        BigDecimal costRatePay
) {
    /** Backwards-compatible 8-arg constructor (pre-Wave-3 shape); margin fields default null. */
    public StatusPatchRequest(
            String newStatus,
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
