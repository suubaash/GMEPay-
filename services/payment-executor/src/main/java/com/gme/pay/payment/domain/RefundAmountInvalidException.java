package com.gme.pay.payment.domain;

import java.math.BigDecimal;

/**
 * Raised when a requested refund amount cannot be accepted against the original payment (gap T2-6).
 *
 * <p>Three distinct, separately-coded cases — a caller must be able to tell "you asked for too much" from
 * "I do not know what the original was", because the first is a client bug and the second is an outage:
 *
 * <ul>
 *   <li>{@link #CODE_EXCEEDS_ORIGINAL} — the requested amount, PLUS everything already refunded for this
 *       transaction, exceeds the original collection amount. This is the cumulative guard: three refunds of
 *       40 against an original of 100 must see the third rejected, not silently over-refund by 20.
 *       {@code retryable=false}.</li>
 *   <li>{@link #CODE_INVALID} — the amount is zero, negative, or denominated in a currency the original
 *       payment was not collected in (a refund is a reversal at the ORIGINAL locked rate, so there is no
 *       cross-currency refund: converting here would invent a rate). {@code retryable=false}.</li>
 *   <li>{@link #CODE_BASIS_UNAVAILABLE} — the original payment could not be read from transaction-mgmt, so
 *       no amount can be validated and no locked-rate pro-rata can be computed. We refuse rather than
 *       refund an unvalidated amount. {@code retryable=true} — it is an availability failure.</li>
 * </ul>
 *
 * <p>Carries a STABLE canonical error code emitted verbatim on the wire because lib-errors is frozen — the
 * same pattern as {@link SchemeOperationNotSupportedException} / {@link LimitCheckUnavailableException}.
 */
public class RefundAmountInvalidException extends PaymentException {

    /** Cumulative refunds would exceed the original payment. */
    public static final String CODE_EXCEEDS_ORIGINAL = "REFUND_AMOUNT_EXCEEDS_ORIGINAL";

    /** The amount is non-positive, or its currency is not the original collection currency. */
    public static final String CODE_INVALID = "REFUND_AMOUNT_INVALID";

    /** The original payment could not be read, so nothing can be validated. */
    public static final String CODE_BASIS_UNAVAILABLE = "REFUND_BASIS_UNAVAILABLE";

    private final String code;
    private final boolean retryable;

    private RefundAmountInvalidException(String code, boolean retryable, String message) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    /**
     * The requested refund would push the cumulative refunded total past the original amount.
     *
     * @param requested        this request's amount
     * @param alreadyRefunded  the amount already refunded for this transaction
     * @param original         the original collection amount
     * @param currency         the collection currency all three are denominated in
     */
    public static RefundAmountInvalidException exceedsOriginal(String txnRef,
                                                               BigDecimal requested,
                                                               BigDecimal alreadyRefunded,
                                                               BigDecimal original,
                                                               String currency) {
        return new RefundAmountInvalidException(CODE_EXCEEDS_ORIGINAL, false,
                "refund of " + plain(requested) + " " + currency + " for " + txnRef
                        + " would exceed the original payment: already refunded " + plain(alreadyRefunded)
                        + ", original " + plain(original) + ", refundable remaining "
                        + plain(original.subtract(alreadyRefunded)));
    }

    /** The amount itself is unusable (non-positive, or a foreign currency). */
    public static RefundAmountInvalidException invalid(String txnRef, String detail) {
        return new RefundAmountInvalidException(CODE_INVALID, false,
                "refund of " + txnRef + " rejected: " + detail);
    }

    /** The original payment could not be read, so the refund cannot be validated or pro-rated. */
    public static RefundAmountInvalidException basisUnavailable(String txnRef, String detail) {
        return new RefundAmountInvalidException(CODE_BASIS_UNAVAILABLE, true,
                "cannot validate a refund of " + txnRef + ": the original payment is unreadable (" + detail
                        + "). Refusing rather than refunding an unvalidated amount.");
    }

    /** The stable canonical error code. */
    public String code() {
        return code;
    }

    /** Whether the caller should retry (true only for {@link #CODE_BASIS_UNAVAILABLE}). */
    public boolean retryable() {
        return retryable;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "?" : v.toPlainString();
    }
}
