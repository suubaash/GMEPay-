package com.gme.pay.scheme.zeropay.dto;

import java.time.Instant;

/**
 * Response DTO for {@code POST /internal/scheme/zeropay/submit}.
 *
 * <p>Field names are chosen to match {@code RestSchemeClient.SchemeApprovalResponse} in
 * payment-executor so Jackson deserialises them without any {@code @JsonProperty} mapping:
 * <ul>
 *   <li>{@code schemeTxnRef}      — the ZeroPay commit-level transaction reference
 *   <li>{@code schemeApprovalCode} — the ZeroPay authorise-level authId (used for refund/cancel)
 *   <li>{@code approvedAt}         — KST commit timestamp
 * </ul>
 * Legacy fields {@code zeroPayTxnRef}, {@code resultCode}, {@code resultMessage},
 * {@code success} are retained for any existing consumers.
 */
public record SubmitPaymentResponse(
        /** ZeroPay commit-level TXN reference — same value as {@code zeroPayTxnRef}. */
        String schemeTxnRef,
        /** ZeroPay authorise-level authId — used as the approval code and for refunds. */
        String schemeApprovalCode,
        /** KST timestamp of the commit call. */
        Instant approvedAt,
        /** Duplicate of {@code schemeTxnRef} for legacy consumers. */
        String zeroPayTxnRef,
        String resultCode,
        String resultMessage,
        boolean success
) {}
