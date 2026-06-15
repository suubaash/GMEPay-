package com.gme.pay.payment.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body for POST /v1/pay — the GMERemit wallet-facing payment confirmation.
 *
 * <p>All KRW money fields are serialised as strings per the project money convention
 * (BigDecimal as JSON string). {@code declineReason} is omitted when null (APPROVED path).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WalletPaymentResponse(
        /** "APPROVED" or "DECLINED". */
        @JsonProperty("status")          String status,
        /** Scheme transaction reference (ZeroPay TXN-xxx string), null when DECLINED. */
        @JsonProperty("schemeTxnRef")    String schemeTxnRef,
        /** Resolved merchant display name. */
        @JsonProperty("merchantName")    String merchantName,
        /** Amount paid to the merchant in KRW (string). */
        @JsonProperty("payAmountKrw")    String payAmountKrw,
        /** Fixed service fee in KRW (string) — ₩500 for GMEREMIT domestic. */
        @JsonProperty("feeKrw")          String feeKrw,
        /** Total charged to the wallet: payAmountKrw + feeKrw (string). */
        @JsonProperty("chargedKrw")      String chargedKrw,
        /** Commit timestamp in KST (ISO-8601 with +09:00 offset), null when DECLINED. */
        @JsonProperty("committedAt")     String committedAt,
        /** Human-readable decline reason, present only when status is DECLINED. */
        @JsonProperty("declineReason")   String declineReason
) {}
