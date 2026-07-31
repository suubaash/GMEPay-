package com.gme.pay.payment.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/** Request body for POST /v1/payments/{id}/cancel and POST /v1/payments/{id}/refund. */
public record CancelPaymentRequest(
        @JsonProperty("reason")        String reason,
        @JsonProperty("reason_detail") String reasonDetail,
        /**
         * T2-7 (optional): scheme CODE the payment was executed on (e.g. {@code "SENDMN"}), so the
         * scheme-side cancel/refund is dispatched to that adapter instead of the ZeroPay default.
         * The {@code X-Scheme-Id} header takes precedence; absent from both = ZeroPay (legacy).
         */
        @JsonProperty("schemeId")      String schemeId,
        /**
         * T2-6 (optional, refund only): the amount to refund. Absent = refund the full refundable
         * remainder, which is the historical behaviour of this endpoint and remains its default.
         *
         * <p>Validated against the original payment before anything moves: it must be positive, in the
         * original collection currency, and — together with everything already refunded for this
         * transaction — no greater than the original amount. Over-refunds are rejected with
         * {@code 422 REFUND_AMOUNT_EXCEEDS_ORIGINAL}, so repeating a partial refund cannot walk a customer
         * past what they paid.
         *
         * <p>The float is credited back pro-rata at the ORIGINAL locked rate (no rate is re-read), and the
         * cumulative refunded amount is persisted so settlement's claw-back nets the right magnitude.
         */
        @JsonProperty("amount")        BigDecimal amount,
        /**
         * T2-6 (optional, refund only): ISO currency of {@code amount}. Must equal the original collection
         * currency — a refund reverses the original booking rather than re-pricing it, so there is no
         * cross-currency refund. Absent = the original collection currency is assumed.
         */
        @JsonProperty("currency")      String currency
) {

    /** Back-compatible 2-arg form; {@code schemeId} defaults to null (ZeroPay default routing). */
    public CancelPaymentRequest(String reason, String reasonDetail) {
        this(reason, reasonDetail, null);
    }

    /** Back-compatible 3-arg (T2-7) form; the refund amount defaults to null = full refund. */
    public CancelPaymentRequest(String reason, String reasonDetail, String schemeId) {
        this(reason, reasonDetail, schemeId, null, null);
    }
}
