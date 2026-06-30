package com.gme.pay.txn.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.gme.pay.txn.domain.model.Transaction;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response row for GET /v1/transactions/refunded?refundedOn=YYYY-MM-DD.
 *
 * <p>The settlement-reconciliation {@code TransactionRecord} projection plus the original payment
 * {@code txnRef} and refund-enrichment fields, so settlement can net a refund against its refund
 * date (cross-date netting) and scheme-adapter can read refund detail (refundAmountKrw, merchantId,
 * qrCodeId, fees) off the projection without reading this DB.
 *
 * <p>Money fields ride as decimal strings (MONEY_CONVENTION.md). {@code @JsonInclude(NON_NULL)}
 * keeps the payload compact for rows missing optional enrichment.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefundedTransactionResponse(
        String txnRef,
        Long partnerId,
        String status,
        String merchantId,
        String qrCodeId,
        String schemeTxnRef,
        /** The original payment's scheme txn reference this refund reverses. */
        String originalPaymentTxnRef,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal targetPayout,
        String targetCcy,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal refundAmountKrw,
        /** V005 gross merchant fee rate snapshot, for NET settlement recompute. */
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal merchantFeeRate,
        Instant refundedAt,
        Instant approvedAt
) {

    public static RefundedTransactionResponse from(Transaction txn) {
        return new RefundedTransactionResponse(
                txn.txnRef(),
                txn.partnerId(),
                txn.status() != null ? txn.status().name() : null,
                txn.merchantId(),
                txn.qrCodeId(),
                txn.schemeTxnRef(),
                txn.originalPaymentTxnRef(),
                txn.targetPayout(),
                txn.targetCcy(),
                txn.refundAmountKrw(),
                txn.merchantFeeRate(),
                txn.refundedAt(),
                txn.approvedAt());
    }
}
