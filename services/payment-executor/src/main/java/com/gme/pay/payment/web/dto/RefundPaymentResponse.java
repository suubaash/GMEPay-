package com.gme.pay.payment.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response body for POST /v1/payments/{id}/refund (full OR partial reversal of an APPROVED txn).
 *
 * <p>T2-6 added the amount fields. Without them the caller could not tell a partial refund from a full one,
 * nor learn how much of the payment was still refundable — so a partner integration had no way to drive a
 * sequence of partial refunds without keeping its own (drift-prone) tally.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefundPaymentResponse(
        @JsonProperty("payment_id")           String paymentId,
        @JsonProperty("status")               String status,
        @JsonProperty("refunded_at")          Instant refundedAt,
        @JsonProperty("prefund_returned_usd") BigDecimal prefundReturnedUsd,
        /** The amount refunded by THIS request (T2-6). Null when the original amount was unreadable. */
        @JsonProperty("refunded_amount")      BigDecimal refundedAmount,
        /** ISO currency of {@code refunded_amount} — the original collection currency. */
        @JsonProperty("refunded_currency")    String refundedCurrency,
        /** Total refunded for this transaction including this request, so the caller need not tally. */
        @JsonProperty("cumulative_refunded_amount") BigDecimal cumulativeRefundedAmount,
        /** True when the transaction is now refunded in full (nothing further is refundable). */
        @JsonProperty("fully_refunded")       Boolean fullyRefunded
) {

    /** Pre-T2-6 shape: a full refund with no amount detail. */
    public RefundPaymentResponse(String paymentId, String status, Instant refundedAt,
                                 BigDecimal prefundReturnedUsd) {
        this(paymentId, status, refundedAt, prefundReturnedUsd, null, null, null, null);
    }
}
