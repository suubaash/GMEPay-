package com.gme.pay.txn.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Response DTO for GET /v1/transactions/{txnRef} and state-transition endpoints.
 * Defined in this module – not a shared lib type.
 *
 * <p>UC-10 additive fields (Phase 4, Stage 1):
 * <ul>
 *   <li>{@code qrSchemeId}             – QR scheme identifier (e.g. "zeropay_kr"). Null until set by scheme-adapter.</li>
 *   <li>{@code krwAmount}              – KRW payout amount. Maps to {@code targetPayout} when {@code targetCcy == "KRW"}; null otherwise until persisted.</li>
 *   <li>{@code payerCurrency}          – ISO-4217 code of the payer's currency (e.g. "USD", "JPY").</li>
 *   <li>{@code payerCurrencyAmount}    – Amount in the payer's currency. Maps to {@code sendAmount} for OVERSEAS path.</li>
 *   <li>{@code appliedFxRate}          – FX rate applied at commit (targetPayout / sendAmount for cross-ccy). Null until commit.</li>
 *   <li>{@code rateTimestamp}          – UTC instant the FX rate was locked. Null until commit.</li>
 *   <li>{@code prefundingDeductedUsd}  – USD deducted from prefunding balance. Null until APPROVED (OVERSEAS only).</li>
 *   <li>{@code statusHistory}          – Ordered list of status transitions (oldest first). Null until tracking is wired.</li>
 *   <li>{@code merchantId}             – Merchant terminal/store identifier from the QR scheme. Null until scheme-adapter sets it.</li>
 *   <li>{@code merchantName}           – Merchant display name. Null until scheme-adapter sets it.</li>
 * </ul>
 *
 * <p>ADDITIVE ONLY – do not rename or remove existing fields; payment-executor and
 * reporting-compliance consume this record concurrently.
 *
 * <p>Money fields use {@link BigDecimal} serialized as decimal strings on the wire
 * (MONEY_CONVENTION.md). {@code @JsonInclude(NON_NULL)} keeps the payload compact
 * for existing consumers that do not yet read the new fields.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionResponse(
        // --- existing fields (DO NOT RENAME / REMOVE) ---
        String txnRef,
        String partnerRef,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal sendAmount,
        String sendCcy,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal targetPayout,
        String targetCcy,
        TransactionStatus status,
        Instant createdAt,
        Instant updatedAt,

        // --- UC-10 additive fields ---
        /** QR scheme identifier, e.g. "zeropay_kr". TODO: populate from scheme-adapter event. */
        String qrSchemeId,
        /** KRW payout amount. Derived from targetPayout when targetCcy=="KRW". TODO: persist separately for multi-leg flows. */
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal krwAmount,
        /** ISO-4217 payer currency, e.g. "USD". Maps to sendCcy on the OVERSEAS path. */
        String payerCurrency,
        /** Amount in the payer's currency. Maps to sendAmount on the OVERSEAS path. */
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal payerCurrencyAmount,
        /** FX rate applied at commit (targetPayout / sendAmount). TODO: lock at commit-time and persist. */
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal appliedFxRate,
        /** UTC instant the FX rate was locked. TODO: persist rateTimestamp at commit-time. */
        Instant rateTimestamp,
        /** USD deducted from the partner's prefunding balance. TODO: populated by prefunding service post-deduction. */
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal prefundingDeductedUsd,
        /** Ordered status-transition history (oldest first). TODO: wire status-history tracking table. */
        List<StatusEntry> statusHistory,
        /** Merchant terminal/store id from the QR scheme. TODO: populate from scheme-adapter. */
        String merchantId,
        /** Merchant display name from the QR scheme. TODO: populate from scheme-adapter. */
        String merchantName
) {
    /**
     * One entry in the status transition history.
     *
     * @param status  the {@link com.gme.pay.txn.domain.model.TransactionStatus} name at this point
     * @param at      UTC instant the status was entered
     */
    public record StatusEntry(String status, Instant at) {}

    /**
     * Maps from domain aggregate — existing fields only.
     * UC-10 additive fields are null (not yet wired to persistence / scheme-adapter).
     * Consumers that only read the original 9 fields are unaffected.
     */
    public static TransactionResponse from(Transaction txn) {
        // Derive UC-10 best-effort values from existing aggregate fields.
        // krwAmount: if the target currency is KRW, re-use targetPayout directly.
        BigDecimal krwAmt = "KRW".equals(txn.targetCcy()) ? txn.targetPayout() : null;
        // payerCurrency / payerCurrencyAmount: for the OVERSEAS path, the payer pays in sendCcy.
        String payerCcy = txn.sendCcy();
        BigDecimal payerAmt = txn.sendAmount();
        // appliedFxRate: compute only when both amounts are present and currencies differ.
        BigDecimal fxRate = null;
        if (txn.sendAmount() != null && txn.targetPayout() != null
                && txn.sendAmount().signum() != 0
                && !txn.sendCcy().equals(txn.targetCcy())) {
            fxRate = txn.targetPayout().divide(txn.sendAmount(), 8, java.math.RoundingMode.HALF_UP);
        }
        return new TransactionResponse(
                txn.txnRef(),
                txn.partnerRef(),
                txn.sendAmount(),
                txn.sendCcy(),
                txn.targetPayout(),
                txn.targetCcy(),
                txn.status(),
                txn.createdAt(),
                txn.updatedAt(),
                // UC-10 additive — null until downstream services wire them in
                null,       // qrSchemeId
                krwAmt,     // krwAmount
                payerCcy,   // payerCurrency
                payerAmt,   // payerCurrencyAmount
                fxRate,     // appliedFxRate
                null,       // rateTimestamp — TODO: lock at commit-time
                null,       // prefundingDeductedUsd — TODO: populated by prefunding post-deduction
                null,       // statusHistory — TODO: wire status-history tracking
                null,       // merchantId — TODO: from scheme-adapter
                null        // merchantName — TODO: from scheme-adapter
        );
    }
}
