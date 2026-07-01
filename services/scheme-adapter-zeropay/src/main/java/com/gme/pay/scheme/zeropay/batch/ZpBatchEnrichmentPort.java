package com.gme.pay.scheme.zeropay.batch;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Cross-service enrichment for the day's ZeroPay batch records.
 *
 * <p>{@link ZpPersistenceBatchDataPort} builds ZP00xx records from this service's own captured
 * rows ({@code zp_committed_txns}). Two of those fields cannot be known at scheme-side commit
 * time and must be sourced from transaction-management:</p>
 *
 * <ul>
 *   <li><b>Refund amount / merchant (IR-1)</b> — the {@code /internal/scheme/zeropay/cancel}
 *       contract passes only the original {@code authId}, so refund legs are captured with
 *       {@code amountKrw=0} and a null/blank merchant. The {@code GET /v1/transactions/refunded}
 *       projection carries the real {@code refundAmountKrw}, {@code merchantId} and
 *       {@code qrCodeId} keyed by the original scheme txnRef.</li>
 *   <li><b>Settlement value date (IR-3)</b> — {@code zp_committed_txns.settlement_date} is null,
 *       so ZP0065/ZP0066 fall back to the business date. The {@code GET /v1/transactions/fx-committed}
 *       projection carries the T+n settlement value date keyed by scheme txnRef.</li>
 * </ul>
 *
 * <p>Both lookups return per-{@code zeropayTxnRef} maps so the data port can enrich each record
 * without an N+1 call. Implementations MUST be best-effort: when the upstream is disabled or
 * unreachable they return empty maps and the data port keeps its pre-enrichment behaviour
 * (zero refund amount / business-date value date). They never throw.</p>
 */
public interface ZpBatchEnrichmentPort {

    /**
     * Refund enrichment for the given KST business (refund) date, keyed by the ZeroPay scheme
     * txnRef of the refund leg (or, where the upstream only knows the original txnRef, that ref).
     *
     * @param businessDate KST refund date
     * @return map of {@code txnRef -> RefundEnrichment}; empty when unavailable
     */
    Map<String, RefundEnrichment> refundEnrichment(LocalDate businessDate);

    /**
     * Settlement value date per committed transaction for the given KST business date, keyed by
     * the ZeroPay scheme txnRef.
     *
     * @param businessDate KST business date
     * @return map of {@code txnRef -> settlementDate}; empty when unavailable
     */
    Map<String, LocalDate> settlementValueDates(LocalDate businessDate);

    /**
     * Real refund leg values sourced from transaction-management. KRW amounts are scale-0
     * {@link BigDecimal} per {@code docs/MONEY_CONVENTION.md}; any may be null when the upstream
     * could not supply them (the data port then keeps the captured/zero value).
     */
    record RefundEnrichment(BigDecimal refundAmountKrw, String merchantId, String qrCodeId) {
    }
}
