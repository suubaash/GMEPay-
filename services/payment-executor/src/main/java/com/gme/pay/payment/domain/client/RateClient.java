package com.gme.pay.payment.domain.client;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Interface to the Rate & FX Engine service (rate-fx).
 * Implementations call rate-fx's REST API; tests use hand-written fakes.
 */
public interface RateClient {

    /**
     * Loads a previously issued, still-locked rate quote from rate-fx via
     * {@code GET /v1/quotes/{quoteId}}.
     *
     * @param quoteId   the quote identifier issued by {@code POST /v1/quotes}
     * @param partnerId the authenticated caller's partner ID; carried through to the
     *                  returned view (GET /v1/quotes performs no server-side ownership check)
     * @return the locked quote
     * @throws com.gme.pay.payment.domain.PaymentException if the quote is expired or
     *         unknown (rate-fx returns {@code 409 RATE_QUOTE_EXPIRED})
     */
    RateQuoteView loadQuote(String quoteId, long partnerId);

    /**
     * Fetches the current mid-market FX rate from the rate provider simulator.
     *
     * <p>Used by the SENDMN overseas path which computes the FX margin inline
     * rather than requiring a pre-issued quote. Backed by
     * {@code GET /v1/rates?base={base}&quote={quote}} on sim-rate-provider (:9101).
     *
     * <p>Default implementation throws {@link UnsupportedOperationException} — only used by
     * implementations that support the SENDMN path. Existing {@code loadQuote}-only lambdas
     * in tests remain valid.
     *
     * @param base  base currency (e.g. "KRW")
     * @param quote quote currency (e.g. "MNT")
     * @return the live rate view with the mid-market rate
     * @throws com.gme.pay.payment.domain.PaymentException if the rate provider is unreachable
     */
    default LiveRate fetchLiveRate(String base, String quote) {
        throw new UnsupportedOperationException(
                "fetchLiveRate not implemented in this RateClient — use RestRateClient");
    }

    /** Mid-market rate returned by sim-rate-provider. */
    record LiveRate(
            String base,
            String quote,
            BigDecimal rate,
            Instant asOf,
            String source
    ) {}

    /** Immutable view of a rate quote returned by rate-fx. */
    record RateQuoteView(
            String quoteId,
            long partnerId,
            String schemeId,
            String direction,
            BigDecimal targetPayout,
            String payoutCurrency,
            BigDecimal collectionUsd,
            BigDecimal payoutUsdCost,
            BigDecimal collectionMarginUsd,
            BigDecimal payoutMarginUsd,
            BigDecimal sendAmount,
            BigDecimal serviceCharge,
            BigDecimal collectionAmount,
            String collectionCurrency,
            BigDecimal offerRateColl,
            BigDecimal crossRate,
            Instant validUntil,
            boolean isSameCcyShortCircuit
    ) {}
}
