package com.gme.pay.payment.domain;

import java.math.BigDecimal;

/**
 * Raised when a PARTIAL refund is asked of a scheme whose adapter contract carries no refund amount, so
 * the only instruction we could actually send is a FULL cancel (gap T2-6).
 *
 * <p><b>Why refusing is the correct behaviour.</b> The hub is now able to reverse part of a payment
 * correctly — pro-rata float at the original locked rate, a partial reversal journal, a cumulative
 * {@code refund_amount_krw} for the settlement claw-back. The scheme leg is not: the ZeroPay adapter's
 * {@code POST /internal/scheme/zeropay/cancel} body is {@code {schemeTxnRef, reason}} and its 전문 cancel
 * carries no amount, and the single-shot cross-border schemes (SENDMN, NEPAL) have no refund round-trip at
 * all (they raise {@link SchemeOperationNotSupportedException} first). Sending a full cancel for a partial
 * refund would refund the customer MORE than was asked at the scheme while our books recorded the smaller
 * figure — a silent, unreconcilable over-refund. Guessing is worse than refusing, so we refuse before any
 * float is moved, any status is written or any journal is posted.
 *
 * <p><b>What unblocks it.</b> An amount-bearing refund on the adapter contract, for a scheme whose IDD
 * defines a partial cancel/refund message. That is scheme-adapter + scheme-certification work; when it
 * lands, the adapter client stops throwing this and honours
 * {@link com.gme.pay.payment.domain.client.SchemeClient.CancelRequest#partialAmount()}.
 *
 * <p>Carries a STABLE canonical error code emitted verbatim on the wire (lib-errors is frozen, so this is
 * not an {@code ErrorCode} enum member) — the same pattern as {@link SchemeOperationNotSupportedException}
 * and {@link OperationalGateException}. {@code retryable=false}: it is a contract fact, not a fault.
 */
public class PartialRefundNotSupportedException extends PaymentException {

    /** Stable canonical error code surfaced to callers. */
    public static final String CODE = "PARTIAL_REFUND_UNSUPPORTED";

    private final String schemeId;

    public PartialRefundNotSupportedException(String schemeId, BigDecimal requestedAmount, String currency) {
        super("scheme " + schemeId + " has no partial-refund instruction: a partial refund of "
                + (requestedAmount == null ? "?" : requestedAmount.toPlainString())
                + " " + (currency == null ? "?" : currency)
                + " cannot be sent, and sending a FULL cancel instead would over-refund the customer at "
                + "the scheme. Refund the full amount, or route this through the manual reversal process "
                + "until the adapter carries a refund amount.");
        this.schemeId = schemeId;
    }

    /** The scheme CODE that cannot perform a partial refund (e.g. {@code "ZEROPAY"}). */
    public String schemeId() {
        return schemeId;
    }

    /** The stable canonical error code ({@link #CODE}). */
    public String code() {
        return CODE;
    }
}
