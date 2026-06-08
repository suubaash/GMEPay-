package com.gme.pay.payment.domain.client;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Interface to the Rate & FX Engine service (rate-fx).
 * Implementations call rate-fx's REST API; tests use hand-written fakes.
 */
public interface RateClient {

    /**
     * Loads a previously issued rate quote from the rate-fx cache.
     *
     * @param quoteId   the quote identifier returned by POST /v1/rates
     * @param partnerId the authenticated caller's partner ID (for ownership check)
     * @return the locked quote
     * @throws com.gme.pay.payment.domain.PaymentException if the quote is expired or invalid
     */
    RateQuoteView loadQuote(String quoteId, long partnerId);

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
