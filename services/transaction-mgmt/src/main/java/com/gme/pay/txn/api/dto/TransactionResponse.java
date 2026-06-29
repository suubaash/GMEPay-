package com.gme.pay.txn.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Response DTO for GET /v1/transactions (paged list) and GET /v1/transactions/{txnRef}.
 *
 * <p>Phase-4 enriched shape — all fields camelCase. Consumers that only read the
 * original 9 fields are unaffected ({@code @JsonInclude(NON_NULL)} keeps the payload compact).
 *
 * <p>Field catalogue:
 * <ul>
 *   <li>{@code txnRef}               – transaction-mgmt internal UUID reference</li>
 *   <li>{@code partnerRef}           – partner reference (legacy: partnerTxnRef for new txns)</li>
 *   <li>{@code sendAmount}           – amount sent by payer (= collectionAmount)</li>
 *   <li>{@code sendCcy}              – payer currency (= collectionCurrency)</li>
 *   <li>{@code targetPayout}         – payout amount in targetCcy / payoutCurrency</li>
 *   <li>{@code targetCcy}            – payout currency</li>
 *   <li>{@code status}               – current TransactionStatus</li>
 *   <li>{@code createdAt}            – UTC creation instant</li>
 *   <li>{@code updatedAt}            – UTC last-update instant</li>
 *   <li>{@code qrSchemeId}           – QR scheme identifier (= schemeId, e.g. "zeropay_kr")</li>
 *   <li>{@code krwAmount}            – KRW payout amount (= targetPayout when targetCcy=="KRW")</li>
 *   <li>{@code payerCurrency}        – ISO-4217 payer currency (= sendCcy / collectionCurrency)</li>
 *   <li>{@code payerCurrencyAmount}  – amount in payer's currency (= sendAmount / collectionAmount)</li>
 *   <li>{@code appliedFxRate}        – FX rate = targetPayout / sendAmount (cross-ccy only)</li>
 *   <li>{@code rateTimestamp}        – UTC instant FX rate was locked (TODO: populate)</li>
 *   <li>{@code prefundingDeductedUsd}– USD deducted from prefunding balance (= prefundDeductedUsd)</li>
 *   <li>{@code statusHistory}        – ordered status transitions (TODO: wire tracking table)</li>
 *   <li>{@code merchantId}           – scheme merchant terminal identifier</li>
 *   <li>{@code merchantName}         – merchant display name (TODO: from scheme-adapter)</li>
 * </ul>
 *
 * <p>ADDITIVE ONLY – do not rename or remove existing fields; payment-executor and
 * reporting-compliance consume this record concurrently.
 *
 * <p>Money fields use {@link BigDecimal} serialized as decimal strings on the wire
 * (MONEY_CONVENTION.md).
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
        /** QR scheme identifier, e.g. "zeropay_kr". Populated from schemeId (V003). */
        String qrSchemeId,
        /** KRW payout amount. Derived from targetPayout when targetCcy=="KRW". */
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal krwAmount,
        /** ISO-4217 payer currency, e.g. "USD". Maps to sendCcy on the OVERSEAS path. */
        String payerCurrency,
        /** Amount in the payer's currency. Maps to sendAmount on the OVERSEAS path. */
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal payerCurrencyAmount,
        /** FX rate applied at commit (targetPayout / sendAmount). */
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal appliedFxRate,
        /** UTC instant the FX rate was locked. TODO: persist rateTimestamp at commit-time. */
        Instant rateTimestamp,
        /** USD deducted from the partner's prefunding balance. Populated from prefundDeductedUsd. */
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal prefundingDeductedUsd,
        /** Ordered status-transition history (oldest first). TODO: wire status-history tracking table. */
        List<StatusEntry> statusHistory,
        /** Merchant terminal/store id from the QR scheme. */
        String merchantId,
        /** Merchant display name from the QR scheme. TODO: populate from scheme-adapter. */
        String merchantName,
        /** V005: gross merchant fee rate snapshotted at creation ("0.0080" = 0.80%); null when unset. */
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal merchantFeeRate,
        /**
         * Scheme approval timestamp — set when the txn transitions to APPROVED (see {@code StatusPatchRequest}).
         * Null on transactions that never reached APPROVED. settlement-reconciliation uses this to enforce
         * the per-window cutoff (morning vs afternoon), so a txn approved after the morning cutoff rolls
         * into the afternoon batch rather than being mis-settled in the morning file.
         */
        Instant approvedAt,
        /**
         * Scheme transaction reference — the QR scheme's settlement id returned when the scheme confirmed
         * it paid the merchant (persisted at APPROVED via {@code StatusPatchRequest}). This is the proof a
         * wallet payment actually reached the merchant; null until APPROVED. Exposed so the read path
         * (BFF / Admin / Partner detail views) shows the REAL scheme confirmation, not a placeholder.
         */
        String schemeTxnRef,
        /**
         * Scheme approval code — the QR scheme's authorization id (used for refund/cancel). Persisted at
         * APPROVED; null until then. Surfaced alongside {@code schemeTxnRef} as the merchant-paid evidence.
         */
        String schemeApprovalCode
) {
    /**
     * One entry in the status transition history.
     *
     * @param status  the {@link com.gme.pay.txn.domain.model.TransactionStatus} name at this point
     * @param at      UTC instant the status was entered
     */
    public record StatusEntry(String status, Instant at) {}

    /**
     * Maps from domain aggregate — includes V003 Phase-4 fields.
     * UC-10 additive fields that have no persistence backing yet remain null.
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
                && txn.sendCcy() != null && !txn.sendCcy().equals(txn.targetCcy())) {
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
                // UC-10 / V003 fields
                txn.schemeId(),     // qrSchemeId — populated from V003 schemeId
                krwAmt,             // krwAmount
                payerCcy,           // payerCurrency
                payerAmt,           // payerCurrencyAmount
                fxRate,             // appliedFxRate
                null,               // rateTimestamp — TODO: lock at commit-time
                txn.prefundDeductedUsd(),   // prefundingDeductedUsd
                null,               // statusHistory — TODO: wire status-history tracking
                txn.merchantId(),   // merchantId — from V003
                null,               // merchantName — TODO: from scheme-adapter
                txn.merchantFeeRate(),  // merchantFeeRate — V005 snapshot
                txn.approvedAt(),       // approvedAt — drives settlement window cutoff
                txn.schemeTxnRef(),     // schemeTxnRef — real scheme settlement id (merchant-paid proof)
                txn.schemeApprovalCode() // schemeApprovalCode — real scheme authorization id
        );
    }
}
