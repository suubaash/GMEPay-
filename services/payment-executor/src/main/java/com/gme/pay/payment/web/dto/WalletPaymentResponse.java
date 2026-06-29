package com.gme.pay.payment.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body for POST /v1/pay — the GMERemit and SENDMN wallet-facing payment confirmation.
 *
 * <p>All KRW and MNT money fields are serialised as strings per the project money convention
 * (BigDecimal as JSON string). {@code declineReason} is omitted when null (APPROVED path).
 * FX fields ({@code fxApplied}, {@code fxRate}, {@code payAmountMnt}) are non-null only for
 * SENDMN overseas (KRW→MNT) payments.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WalletPaymentResponse(
        /** "APPROVED" or "DECLINED". */
        @JsonProperty("status")          String status,
        /** transaction-mgmt reference — GET /v1/transactions/{txnRef} returns the full value breakdown. Null when DECLINED. */
        @JsonProperty("txnRef")          String txnRef,
        /** Scheme transaction reference (ZeroPay TXN-xxx string), null when DECLINED. */
        @JsonProperty("schemeTxnRef")    String schemeTxnRef,
        /** Resolved merchant display name. */
        @JsonProperty("merchantName")    String merchantName,
        /** Amount paid to the merchant in KRW (string). */
        @JsonProperty("payAmountKrw")    String payAmountKrw,
        /** Fixed service fee in KRW (string) — ₩500. */
        @JsonProperty("feeKrw")          String feeKrw,
        /** Total charged to the wallet: payAmountKrw + feeKrw (string). */
        @JsonProperty("chargedKrw")      String chargedKrw,
        /** Commit timestamp in KST (ISO-8601 with +09:00 offset), null when DECLINED. */
        @JsonProperty("committedAt")     String committedAt,
        /** Human-readable decline reason, present only when status is DECLINED. */
        @JsonProperty("declineReason")   String declineReason,
        /** True for SENDMN overseas payments; null for domestic. */
        @JsonProperty("fxApplied")       Boolean fxApplied,
        /** Offer FX rate applied (MNT per KRW, string), null for domestic. */
        @JsonProperty("fxRate")          String fxRate,
        /** MNT amount credited to the recipient, null for domestic. */
        @JsonProperty("payAmountMnt")    String payAmountMnt
) {}
