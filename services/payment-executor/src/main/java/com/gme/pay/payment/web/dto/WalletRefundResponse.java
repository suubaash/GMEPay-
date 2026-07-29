package com.gme.pay.payment.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body for POST /v1/pay/{schemeTxnRef}/refund.
 *
 * <p>{@code errorMessage} / {@code errorCode} are omitted when null (successful refund path).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WalletRefundResponse(
        /** "REFUNDED" or "FAILED". */
        @JsonProperty("status")         String status,
        /** The original scheme transaction reference. */
        @JsonProperty("schemeTxnRef")   String schemeTxnRef,
        /** The authorise-level authId used for the refund call. */
        @JsonProperty("authId")         String authId,
        /** KST timestamp of the refund (ISO-8601). Null on failure. */
        @JsonProperty("refundedAt")     String refundedAt,
        /** Error message, present only on failure. */
        @JsonProperty("errorMessage")   String errorMessage,
        /**
         * T2-7: stable machine-readable failure code, present only on failure. Today either
         * {@code SCHEME_OPERATION_UNSUPPORTED} (the corridor has no scheme refund path — do not retry,
         * escalate to the manual reversal process) or {@code SCHEME_REFUND_FAILED} (the scheme declined
         * or was unreachable).
         */
        @JsonProperty("errorCode")      String errorCode
) {

    /** Back-compatible 5-arg form; {@code errorCode} defaults to null. */
    public WalletRefundResponse(String status, String schemeTxnRef, String authId,
                                String refundedAt, String errorMessage) {
        this(status, schemeTxnRef, authId, refundedAt, errorMessage, null);
    }
}
