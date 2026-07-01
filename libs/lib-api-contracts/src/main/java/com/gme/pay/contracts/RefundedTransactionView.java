package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Canonical read DTO for one refund leg returned by transaction-mgmt's
 * {@code GET /v1/transactions/refunded?refundedOn=YYYY-MM-DD}.
 *
 * <p><b>Why this exists.</b> Three services bind this endpoint and each rolled
 * its OWN ad-hoc wire record with DIFFERENT field names — Jackson binds by
 * name, so the divergence silently produced {@code null}s at the seam:
 * <ul>
 *   <li>transaction-mgmt (the producer) emits {@code RefundedTransactionResponse}
 *       with {@code txnRef}, {@code originalPaymentTxnRef}, {@code refundAmountKrw},
 *       {@code merchantId}, {@code qrCodeId}, {@code schemeTxnRef}, {@code refundedAt}.</li>
 *   <li>settlement-reconciliation read {@code refundTxnRef} / {@code originalTxnRef}
 *       / {@code refundAmount} / {@code refundedOn} (all wrong → null).</li>
 *   <li>scheme-adapter-zeropay read {@code refundSchemeTxnRef} /
 *       {@code originalSchemeTxnRef} (also wrong → null).</li>
 * </ul>
 *
 * <p>This type mirrors the <b>producer's</b> field names verbatim (the producer
 * is the source of truth) so all three can converge on one type during the
 * wiring step. {@code settlementDate} is included additively (the value date a
 * refund nets onto); the producer may populate it from its settlement-window
 * projection, NULL until then.
 *
 * <p>Money fields ride as decimal STRINGs ({@code docs/MONEY_CONVENTION.md}).
 * {@code @JsonInclude(NON_NULL)} keeps rows missing optional enrichment compact,
 * matching transaction-mgmt's {@code RefundedTransactionResponse}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefundedTransactionView(
        /** The refund transaction's own internal reference (producer: {@code txnRef}). */
        String txnRef,
        /** The original payment txn this refund reverses (producer: {@code originalPaymentTxnRef}). */
        String originalPaymentTxnRef,
        Long partnerId,
        String status,
        String merchantId,
        String qrCodeId,
        /** The refund's scheme-side transaction reference. */
        String schemeTxnRef,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal refundAmountKrw,
        String targetCcy,
        /** V005 gross merchant fee-rate snapshot, for NET settlement recompute; nullable. */
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal merchantFeeRate,
        /** When the refund was approved/booked (producer: {@code refundedAt}). */
        Instant refundedAt,
        Instant approvedAt,
        /** Settlement value date the refund nets onto (ISO yyyy-MM-dd); additive, nullable. */
        LocalDate settlementDate) {
}
