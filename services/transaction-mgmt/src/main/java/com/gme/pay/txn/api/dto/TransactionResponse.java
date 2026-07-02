package com.gme.pay.txn.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.gme.pay.txn.domain.model.CustomerStatusText;
import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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
        String schemeApprovalCode,

        // --- CS quick-wins additive fields ---
        /**
         * OI-01 failure-reason code recorded when the txn entered FAILED (e.g. "APPROVAL_TIMEOUT").
         * Exposed from the domain aggregate (previously not serialized). Null unless FAILED with a reason.
         */
        String failureReason,
        /** Plain-language label for {@code status} (e.g. APPROVED → "Payment approved"). */
        String statusLabel,
        /**
         * Customer-friendly sentence mapped from {@code failureReason} (falls back to the raw reason).
         * Null unless a failure reason is present.
         */
        String declineReasonText,
        /**
         * End-customer / wallet identifier carried on the wallet payment (captured at create, V011).
         * Lets support look a payment up by what the CUSTOMER holds. Null on legacy rows.
         */
        String userRef
) {
    /**
     * One entry in the status transition history.
     *
     * @param status      the {@link TransactionStatus} name at this point
     * @param statusLabel plain-language label for the status
     * @param at          UTC instant the status was entered
     * @param note        optional context (e.g. the decline reason, or who force-resolved it)
     */
    public record StatusEntry(String status, String statusLabel, Instant at, String note) {}

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
                buildStatusHistory(txn),    // statusHistory — derived from stored timestamps
                txn.merchantId(),   // merchantId — from V003
                null,               // merchantName — TODO: from scheme-adapter
                txn.merchantFeeRate(),  // merchantFeeRate — V005 snapshot
                txn.approvedAt(),       // approvedAt — drives settlement window cutoff
                txn.schemeTxnRef(),     // schemeTxnRef — real scheme settlement id (merchant-paid proof)
                txn.schemeApprovalCode(), // schemeApprovalCode — real scheme authorization id
                // --- CS quick-wins ---
                txn.failureReason(),                                    // failureReason (from domain)
                CustomerStatusText.statusLabel(txn.status()),           // statusLabel
                CustomerStatusText.declineReasonText(txn.failureReason()), // declineReasonText
                txn.userRef()                                           // userRef (V011)
        );
    }

    /**
     * Derives an ordered status timeline from the timestamps already stored on the aggregate — no
     * separate transition-log table is needed. Entries are emitted only for milestones we have a
     * real {@code at} for, then sorted oldest-first:
     * <ul>
     *   <li>CREATED — always ({@code createdAt}).</li>
     *   <li>APPROVED — when {@code approvedAt} / {@code committedAt} is set.</li>
     *   <li>REVERSED / REFUNDED — when {@code refundedAt} is set (money-terminal); labelled by the
     *       current status so a reversed txn reads "Reversed / refunded".</li>
     *   <li>Current status — a final entry stamped at {@code updatedAt} when the txn has moved past
     *       CREATED and the current status was not already emitted above (covers FAILED with its
     *       decline reason as the note, UNCERTAIN, CANCELLED, etc.). For a force-resolved txn the
     *       resolution reason rides as the note.</li>
     * </ul>
     * Never returns null — a freshly-CREATED txn yields a single-entry list.
     */
    static List<StatusEntry> buildStatusHistory(Transaction txn) {
        List<StatusEntry> history = new ArrayList<>();
        TransactionStatus current = txn.status();

        // 1) Creation — always present.
        history.add(entry(TransactionStatus.CREATED, txn.createdAt(), null));

        // 2) Approval milestone — the commit instant (approvedAt, else committedAt).
        Instant approvedInstant = txn.approvedAt() != null ? txn.approvedAt() : txn.committedAt();
        if (approvedInstant != null) {
            history.add(entry(TransactionStatus.APPROVED, approvedInstant, null));
        }

        // 3) Reversal / refund milestone — refundedAt is stamped for both REVERSED and REFUNDED.
        if (txn.refundedAt() != null
                && (current == TransactionStatus.REVERSED || current == TransactionStatus.REFUNDED)) {
            history.add(entry(current, txn.refundedAt(), noteForCurrent(txn)));
        }

        // 4) Current status as a terminal marker, when it is not one of the milestones already
        //    emitted (e.g. FAILED / UNCERTAIN / CANCELLED / SCHEME_SENT / PENDING_DEBIT). Skipped
        //    for a bare CREATED txn (already in the list) to avoid a duplicate entry.
        if (current != TransactionStatus.CREATED
                && current != TransactionStatus.APPROVED
                && !(current == TransactionStatus.REVERSED || current == TransactionStatus.REFUNDED)) {
            Instant at = txn.updatedAt() != null ? txn.updatedAt() : txn.createdAt();
            history.add(entry(current, at, noteForCurrent(txn)));
        }

        // Oldest-first; null instants sort last defensively.
        history.sort((a, b) -> {
            if (a.at() == null) return 1;
            if (b.at() == null) return -1;
            return a.at().compareTo(b.at());
        });
        return history;
    }

    private static StatusEntry entry(TransactionStatus status, Instant at, String note) {
        return new StatusEntry(status.name(), CustomerStatusText.statusLabel(status), at, note);
    }

    /** Note for the current-status entry: decline reason for FAILED, else the operator resolution reason. */
    private static String noteForCurrent(Transaction txn) {
        if (txn.status() == TransactionStatus.FAILED) {
            return CustomerStatusText.declineReasonText(txn.failureReason());
        }
        return txn.resolutionReason();
    }
}
