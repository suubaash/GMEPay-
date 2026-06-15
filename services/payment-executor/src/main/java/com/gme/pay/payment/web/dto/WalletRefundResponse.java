package com.gme.pay.payment.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body for POST /v1/pay/{schemeTxnRef}/refund.
 *
 * <p>{@code errorMessage} is omitted when null (successful refund path).
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
        @JsonProperty("errorMessage")   String errorMessage
) {}
