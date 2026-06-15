package com.gme.pay.payment.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.payment.domain.PaymentException;
import com.gme.pay.payment.domain.client.RateClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * REST adapter for rate lookups.
 *
 * <ul>
 *   <li>{@link #loadQuote} — {@code GET /v1/quotes/{quoteId}} against rate-fx
 *       service ({@code gmepay.rate-fx.base-url}, default
 *       {@code http://rate-fx:8080}); returns the TTL-locked {@code StoredQuote}.
 *   <li>{@link #fetchLiveRate} — {@code GET /v1/rates?base=&quote=} against
 *       sim-rate-provider ({@code gmepay.sim-rate-provider.base-url},
 *       default {@code http://localhost:9101}).
 * </ul>
 */
@Component
@Primary
public class RestRateClient implements RateClient {

    private final RestClient restClient;
    private final RestClient simRateClient;

    @Autowired
    public RestRateClient(
            RestClient.Builder builder,
            @Value("${gmepay.rate-fx.base-url:http://rate-fx:8080}") String baseUrl,
            @Value("${gmepay.sim-rate-provider.base-url:http://localhost:9101}") String simRateBaseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.simRateClient = builder.clone().baseUrl(simRateBaseUrl).build();
    }

    /** Test constructor — both clients pre-built. */
    RestRateClient(RestClient restClient, RestClient simRateClient) {
        this.restClient = restClient;
        this.simRateClient = simRateClient;
    }

    /** Legacy test constructor — only quote-lookup client; live-rate will NPE. */
    RestRateClient(RestClient restClient) {
        this.restClient = restClient;
        this.simRateClient = restClient; // fallback for legacy tests
    }

    @Override
    public RateQuoteView loadQuote(String quoteId, long partnerId) {
        try {
            StoredQuoteResponse body = restClient.get()
                    .uri("/v1/quotes/{quoteId}", quoteId)
                    .retrieve()
                    .body(StoredQuoteResponse.class);

            if (body == null) {
                throw new PaymentException("rate-fx returned empty body for quote " + quoteId);
            }
            return body.toView(partnerId);
        } catch (RestClientResponseException ex) {
            // rate-fx returns 409 RATE_QUOTE_EXPIRED for unknown/expired quotes.
            throw new PaymentException(
                    "rate-fx GET /v1/quotes/" + quoteId + " failed: "
                            + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException(
                    "rate-fx GET /v1/quotes/" + quoteId + " failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public LiveRate fetchLiveRate(String base, String quote) {
        try {
            LiveRateResponse body = simRateClient.get()
                    .uri("/v1/rates?base={base}&quote={quote}", base, quote)
                    .retrieve()
                    .body(LiveRateResponse.class);

            if (body == null) {
                throw new PaymentException(
                        "sim-rate-provider returned empty body for " + base + "/" + quote);
            }
            return body.toView();
        } catch (RestClientResponseException ex) {
            throw new PaymentException(
                    "sim-rate-provider GET /v1/rates failed for " + base + "/" + quote + ": "
                            + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException(
                    "sim-rate-provider GET /v1/rates failed for " + base + "/" + quote + ": "
                            + ex.getMessage(), ex);
        }
    }

    /** Wire format for sim-rate-provider response: {base, quote, rate, asOf, source}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record LiveRateResponse(
            String base,
            String quote,
            BigDecimal rate,
            Instant asOf,
            String source
    ) {
        LiveRate toView() {
            return new LiveRate(base, quote, rate, asOf != null ? asOf : Instant.now(), source);
        }
    }

    /**
     * Wire format for the rate-fx {@code GET /v1/quotes/{quoteId}} response —
     * mirrors {@code com.gme.pay.ratefx.quote.StoredQuote}. Money/rate fields are
     * decimal strings (MONEY_CONVENTION.md) and bind cleanly to {@link BigDecimal}.
     *
     * <p>{@code StoredQuote} does not carry {@code partnerId}, {@code schemeId},
     * {@code direction} or a pre-computed {@code serviceCharge}; see {@link #toView}
     * for how those are reconciled into {@link RateQuoteView}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record StoredQuoteResponse(
            String quoteId,
            String collectionCurrency,
            String settleACurrency,
            String settleBCurrency,
            String payoutCurrency,
            BigDecimal targetPayout,
            BigDecimal payoutUsdCost,
            BigDecimal collectionUsd,
            BigDecimal collectionMarginUsd,
            BigDecimal payoutMarginUsd,
            BigDecimal sendAmount,
            BigDecimal collectionAmount,
            BigDecimal offerRateColl,
            BigDecimal crossRate,
            boolean shortCircuit,
            Instant createdAt,
            Instant expiresAt
    ) {
        /**
         * Maps the stored quote into the domain view.
         *
         * @param partnerId the authenticated caller's partner ID, carried from the
         *                  {@code loadQuote} argument (GET /v1/quotes performs no
         *                  server-side ownership check, so it is not echoed back)
         */
        RateQuoteView toView(long partnerId) {
            // serviceCharge is the collection-side fee = collectionAmount - sendAmount;
            // StoredQuote does not store it, so derive it when both legs are present.
            BigDecimal serviceCharge =
                    (collectionAmount != null && sendAmount != null)
                            ? collectionAmount.subtract(sendAmount)
                            : BigDecimal.ZERO;
            return new RateQuoteView(
                    quoteId,
                    partnerId,
                    null,             // schemeId — not part of StoredQuote; loadQuote signature does not supply it
                    null,             // direction — not part of StoredQuote; loadQuote signature does not supply it
                    targetPayout,
                    payoutCurrency,
                    collectionUsd,
                    payoutUsdCost,
                    collectionMarginUsd,
                    payoutMarginUsd,
                    sendAmount,
                    serviceCharge,
                    collectionAmount,
                    collectionCurrency,
                    offerRateColl,
                    crossRate,
                    expiresAt,        // validUntil <- expiresAt
                    shortCircuit);    // isSameCcyShortCircuit <- shortCircuit
        }
    }
}
