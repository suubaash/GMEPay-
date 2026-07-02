package com.gme.pay.bff.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Read-only view of transaction-mgmt. Production implementation calls
 * {@code GET /v1/transactions/{id}} and a list endpoint; the Phase-1 default is
 * an in-memory stub.
 *
 * <p>Phase C2 adds a filtered, paginated {@link #list(Filter)} for the Admin UI
 * transactions search page.
 *
 * <p>UC-10 (Phase 4, Stage 1): {@link TransactionSummary} is enriched with additional
 * read fields required by UC-10-02 / UC-10-03. All new fields are additive and nullable
 * so existing stub implementations and tests remain binary-compatible.
 */
public interface TransactionMgmtClient {

    /** Fetch a single transaction by id; returns {@code null} when unknown. */
    TransactionSummary getTransaction(String txnId);

    /**
     * Fetch the {@code limit} most recent transactions across all partners
     * (Admin UI) or for one partner (Portal UI).
     *
     * @param partnerId optional — null means all partners
     * @param limit     maximum number of rows to return
     */
    List<TransactionSummary> recent(String partnerId, int limit);

    /**
     * Filtered + paginated transaction search. Production calls
     * {@code GET /v1/transactions?...}; Phase-1 stub filters the in-memory list.
     */
    Page<TransactionSummary> list(Filter filter);

    // -------- Ops wave: 360° search + operator resolve -----------------------
    //
    // Additive default methods (never break existing anonymous test fakes /
    // lambda implementations). Both real implementations (rest + stub) override.

    /**
     * 360° transaction search — the Ops control-tower drill-down. Routes to
     * transaction-mgmt's {@code GET /v1/transactions/search?q=...&status=...&partnerId=...}
     * free-text + facet search (broader than {@link #list(Filter)}'s date/state filter).
     * Returns the mapped result rows; empty page when nothing matches or upstream degrades.
     *
     * <p>Default: falls back to {@link #list(Filter)} on {@code (partnerId, state)} so a
     * stub / minimal fake still answers.
     */
    default Page<TransactionSummary> search(SearchQuery query) {
        Filter f = new Filter(query.partnerId(), null, query.status(), null, null,
                query.page(), query.size());
        return list(f);
    }

    /**
     * Convenience overload retaining the old 5-arg {@link SearchQuery} shape (no
     * {@code userRef} / {@code reference}). Kept so existing callers / test fakes that
     * only know txnRef/partnerId/status keep compiling. Delegates to the full
     * {@link #search(SearchQuery)}.
     */
    default Page<TransactionSummary> search(String q, String partnerId, String status, int page, int size) {
        return search(new SearchQuery(q, partnerId, status, null, null, page, size));
    }

    /**
     * Operator resolution of an UNCERTAIN / stuck transaction. Routes to
     * transaction-mgmt's {@code POST /v1/transactions/{ref}/resolve} carrying the
     * operator's resolution (e.g. {@code FORCE_APPROVE} | {@code FORCE_FAIL} |
     * {@code MARK_REVIEWED}) + reason. Returns the fresh {@link TransactionSummary}
     * in its post-resolution state. Upstream 404 (unknown ref) / 409 (illegal state)
     * propagate as {@code ResponseStatusException} from the rest impl.
     */
    default TransactionSummary resolve(String txnRef, String resolution, String actor, String reason) {
        throw new UnsupportedOperationException(
                "resolve is not implemented by " + getClass().getName());
    }

    /**
     * Free-text + facet search criteria for {@link #search(SearchQuery)}. All fields
     * optional; {@code page} 0-indexed.
     *
     * <p>CS support-read additive fields (forwarded to transaction-mgmt's
     * {@code GET /v1/transactions/search}):
     * <ul>
     *   <li>{@code userRef}   – the end-customer / wallet id, so a support agent can look up
     *       every transaction of one customer.</li>
     *   <li>{@code reference} – the partner's own reference for the transaction.</li>
     * </ul>
     * {@code q} remains the free-text term (matched by transaction-mgmt against txnRef).
     */
    record SearchQuery(
            String q,
            String partnerId,
            String status,
            String userRef,
            String reference,
            int page,
            int size
    ) {}

    /**
     * Partner-facing transaction summary (UC-10-02).
     *
     * <p>Original 6 fields are preserved exactly. UC-10 additive fields follow:
     * <ul>
     *   <li>{@code qrSchemeId}            – QR scheme identifier (e.g. "zeropay_kr"). Null until set by scheme-adapter.</li>
     *   <li>{@code krwAmount}             – KRW payout amount (BigDecimal-as-string on the wire). Null for non-KRW payouts.</li>
     *   <li>{@code payerCurrency}         – ISO-4217 payer currency. Maps to sendCcy on the OVERSEAS path.</li>
     *   <li>{@code payerCurrencyAmount}   – Amount in the payer's currency (BigDecimal-as-string). Maps to sendAmount.</li>
     *   <li>{@code appliedFxRate}         – FX rate at commit (BigDecimal-as-string). Null for same-currency transactions.</li>
     *   <li>{@code rateTimestamp}         – UTC instant the FX rate was locked. Null until persisted.</li>
     *   <li>{@code prefundingDeductedUsd} – USD deducted from prefunding (BigDecimal-as-string). Null until APPROVED.</li>
     * </ul>
     *
     * <p>IMPORTANT: money fields are BigDecimal; the BFF MUST NOT cast them to JS Number
     * (precision loss). The UI layer reads them as strings.
     *
     * <p>ADDITIVE ONLY — do not rename or remove fields; existing stubs pass null for new fields.
     */
    record TransactionSummary(
            // --- original fields ---
            String txnId,
            String partnerId,
            String state,
            BigDecimal amount,
            String currency,
            Instant committedAt,
            // --- UC-10-02 additive fields ---
            /** QR scheme identifier. TODO: populate from scheme-adapter event. */
            String qrSchemeId,
            /** KRW payout amount. TODO: persist separately for multi-leg flows. */
            BigDecimal krwAmount,
            /** ISO-4217 payer currency. */
            String payerCurrency,
            /** Amount in the payer's currency. */
            BigDecimal payerCurrencyAmount,
            /** Applied FX rate at commit. TODO: lock and persist at commit-time. */
            BigDecimal appliedFxRate,
            /** UTC instant FX rate was locked. TODO: persist at commit-time. */
            Instant rateTimestamp,
            /** USD deducted from prefunding balance. TODO: populated by prefunding post-deduction. */
            BigDecimal prefundingDeductedUsd,
            // --- scheme-confirmation + merchant fields (real values from transaction-mgmt) ---
            /** Scheme transaction reference — proof the QR scheme paid the merchant. Null until APPROVED. */
            String schemeTxnRef,
            /** Scheme approval code (authorization id). Null until APPROVED. */
            String schemeApprovalCode,
            /** Merchant terminal/store id from the QR scheme. */
            String merchantId,
            /** UTC instant the scheme approved the payment. Null until APPROVED. */
            Instant approvedAt,
            // --- CS support-read additive fields (from transaction-mgmt) ---
            /** Machine failure reason code for a failed/declined txn. Null for successful / older txns. */
            String failureReason,
            /** Plain-language label for {@code state} (e.g. "Approved", "Declined"). Null on older txns. */
            String statusLabel,
            /** Human-readable decline reason from the scheme. Null when not declined / older txns. */
            String declineReasonText,
            /** Ordered status-transition history, oldest first. Non-null from transaction-mgmt; null on older txns. */
            List<StatusEntry> statusHistory
    ) {
        /**
         * Convenience factory for existing stub/test code that only knows the original 6 fields.
         * New UC-10 + scheme-confirmation + CS support-read fields default to {@code null}.
         */
        public static TransactionSummary of(
                String txnId, String partnerId, String state,
                BigDecimal amount, String currency, Instant committedAt) {
            return new TransactionSummary(txnId, partnerId, state, amount, currency, committedAt,
                    null, null, null, null, null, null, null,
                    null, null, null, null,
                    null, null, null, null);
        }
    }

    /**
     * One entry in the status-transition history (UC-10-03; enriched for CS support-read).
     *
     * @param status       TransactionStatus name at this point (e.g. "APPROVED")
     * @param statusLabel  plain-language label for {@code status} (e.g. "Approved"); null on older txns
     * @param at           UTC instant the status was entered
     * @param note         free-text note attached to the transition; null when none
     */
    record StatusEntry(String status, String statusLabel, Instant at, String note) {
        /** Back-compat factory for the original 2-arg shape (no label / note). */
        public static StatusEntry of(String status, Instant at) {
            return new StatusEntry(status, null, at, null);
        }
    }

    /**
     * Filter shape for {@link #list(Filter)}. All criterion fields are optional;
     * a {@code null} means "do not filter on this field". Page is 0-indexed.
     */
    record Filter(
            String partnerId,
            String schemeId,
            String state,
            LocalDate fromDate,
            LocalDate toDate,
            int page,
            int size
    ) {}

    /**
     * Generic page envelope used by paginated client methods. The fields mirror
     * the BFF's {@code Page<T>} DTO so the wire shape passes through unchanged.
     */
    record Page<T>(List<T> content, int page, int size, long total) {}
}
