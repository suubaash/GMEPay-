package com.gme.pay.bff.client;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read-only quote preview surface backed by {@code rate-fx}. Production
 * implementation will call {@code POST /v1/rates} on {@code rate-fx}; the
 * Phase-1 default is an in-memory stub so the Admin UI Rates Preview page can
 * render without booting rate-fx.
 *
 * <p>The preview mirrors the 5-step USD-pivot math documented in
 * {@code docs/MONEY_CONVENTION.md} and the rate engine's javadoc:
 * <ol>
 *   <li>Convert {@code amount} in {@code fromCcy} to USD using the source-side
 *       treasury rate (collection leg).
 *   <li>Apply the collection-side partner margin to compute the USD pool entry
 *       ({@code collectionUsd}).
 *   <li>Convert the USD pool amount to {@code toCcy} using the payout-side
 *       treasury rate ({@code payoutUsdCost}).
 *   <li>Apply the payout-side partner margin to compute the customer-facing
 *       payout ({@code payoutAmount}).
 *   <li>Derive {@code offerRateColl} and {@code crossRate} for display.
 * </ol>
 *
 * <p>USD-pool math runs at full precision ({@code MathContext(20)}); rounding
 * is applied only at output points (collection/payout amounts) so the pool
 * identity holds (tolerance 0.01 USD).
 */
public interface RatesClient {

    /**
     * Computes a quote preview for the Admin UI Rates Preview page. The result
     * is not a binding quote (no quote id, no TTL) — for that, partners call
     * {@code rate-fx} directly via the gateway. Returns {@code null} only on a
     * hard upstream failure; otherwise a fully populated preview.
     */
    RateQuotePreview previewQuote(RateQuoteRequest req);

    /**
     * Input shape for {@link #previewQuote(RateQuoteRequest)}. {@code amount}
     * is the amount the partner is collecting from (or paying out to) the
     * customer, in {@code fromCcy}. {@code direction} is one of
     * {@code "COLLECTION"} (customer pays {@code fromCcy}, partner receives
     * {@code toCcy}) or {@code "PAYOUT"} (partner sends {@code fromCcy},
     * customer receives {@code toCcy}).
     */
    record RateQuoteRequest(
            String fromCcy,
            String toCcy,
            BigDecimal amount,
            String direction,
            long partnerId
    ) {}

    /**
     * Preview shape returned to the Admin UI. All BigDecimal fields are in
     * major currency units; USD-pool fields are at full precision while
     * {@code collectionAmount} / {@code payoutAmount} are already rounded to
     * the currency's display scale ({@code lib-money/CurrencyScale}).
     *
     * @param shortCircuit true if {@code fromCcy.equals(toCcy)} — the engine
     *     skips the USD pivot and returns {@code amount} unchanged on both
     *     sides with zero margin.
     */
    record RateQuotePreview(
            BigDecimal collectionAmount,
            String collectionCurrency,
            BigDecimal payoutAmount,
            String payoutCurrency,
            BigDecimal collectionUsd,
            BigDecimal payoutUsdCost,
            BigDecimal collectionMarginUsd,
            BigDecimal payoutMarginUsd,
            BigDecimal offerRateColl,
            BigDecimal crossRate,
            boolean shortCircuit,
            Instant quotedAt
    ) {}
}
