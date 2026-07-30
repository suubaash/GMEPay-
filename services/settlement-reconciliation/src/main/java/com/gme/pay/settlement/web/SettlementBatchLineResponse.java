package com.gme.pay.settlement.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.gme.pay.settlement.persistence.SettlementLineEntity;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One persisted {@code settlement_lines} row — a single transaction as it was settled (GAP T4-5).
 *
 * <p>{@link #amount} is SIGNED: positive is a payment credited to the merchant, negative is a
 * refund clawed back out of a later window. Σ signed amount per merchant is exactly the net GMEPay+
 * requested the scheme credit, which is why the statement can be summed from these rows and will
 * agree with the batch's booked net rather than with a fresh recomputation of live transaction gross.
 *
 * @param txnRef              the platform transaction reference
 * @param schemeRef           the scheme's own reference for the same transaction
 * @param merchantId          the merchant this line settles for
 * @param amount              SIGNED settled amount (payment positive, refund clawback negative)
 * @param currency            line currency
 * @param matched             set true once this line reconciled cleanly against the scheme's file
 * @param bookedSettlementAmount per-line booked figure where booking is per line
 * @param roundingResidual    per-line Addendum-001 residual
 * @param settlementRoundingMode the rounding mode used
 * @param settlementType      'N' | 'G'
 * @param merchantFeeRate     the V005 fee rate snapshot applied (0 for GROSS)
 * @param approvedAt          scheme approval instant
 */
public record SettlementBatchLineResponse(
        String txnRef,
        String schemeRef,
        String merchantId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        boolean matched,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal bookedSettlementAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal roundingResidual,
        String settlementRoundingMode,
        String settlementType,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal merchantFeeRate,
        Instant approvedAt) {

    public static SettlementBatchLineResponse from(SettlementLineEntity l) {
        return new SettlementBatchLineResponse(
                l.getTxnRef(),
                l.getSchemeRef(),
                l.getMerchantId(),
                l.getAmount(),
                l.getCurrency(),
                l.isMatched(),
                l.getBookedSettlementAmount(),
                l.getRoundingResidual(),
                l.getSettlementRoundingMode(),
                l.getSettlementType(),
                l.getMerchantFeeRate(),
                l.getApprovedAt());
    }
}
