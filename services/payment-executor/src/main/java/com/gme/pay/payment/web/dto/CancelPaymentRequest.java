package com.gme.pay.payment.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request body for POST /v1/payments/{id}/cancel and POST /v1/payments/{id}/refund. */
public record CancelPaymentRequest(
        @JsonProperty("reason")        String reason,
        @JsonProperty("reason_detail") String reasonDetail,
        /**
         * T2-7 (optional): scheme CODE the payment was executed on (e.g. {@code "SENDMN"}), so the
         * scheme-side cancel/refund is dispatched to that adapter instead of the ZeroPay default.
         * The {@code X-Scheme-Id} header takes precedence; absent from both = ZeroPay (legacy).
         */
        @JsonProperty("schemeId")      String schemeId
) {

    /** Back-compatible 2-arg form; {@code schemeId} defaults to null (ZeroPay default routing). */
    public CancelPaymentRequest(String reason, String reasonDetail) {
        this(reason, reasonDetail, null);
    }
}
