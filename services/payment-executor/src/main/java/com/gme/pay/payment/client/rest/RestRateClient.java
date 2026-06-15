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
 *   <li>{@link #loadQuote} — {@code POST /v1/rates} against rate-fx service
 *       ({@code gmepay.rate-fx.base-url}, default {@code http://rate-fx:8080}).
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
            QuoteLookupRequest req = new QuoteLookupRequest(quoteId, partnerId);
            RateQuoteResponse body = restClient.post()
                    .uri("/v1/rates")
                    .body(req)
                    .retrieve()
                    .body(RateQuoteResponse.class);

            if (body == null) {
                throw new PaymentException("rate-fx returned empty body for quote " + quoteId);
            }
            return body.toView();
        } catch (RestClientResponseException ex) {
            throw new PaymentException(
                    "rate-fx POST /v1/rates failed for quote " + quoteId + ": "
                            + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException(
                    "rate-fx POST /v1/rates failed for quote " + quoteId + ": " + ex.getMessage(), ex);
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

    /** Wire format for the quote-lookup request body. */
    record QuoteLookupRequest(String quoteId, long partnerId) {}

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

    /** Wire format for the quote-lookup response (mirrors {@link RateQuoteView}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RateQuoteResponse(
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
    ) {
        RateQuoteView toView() {
            return new RateQuoteView(
                    quoteId, partnerId, schemeId, direction,
                    targetPayout, payoutCurrency,
                    collectionUsd, payoutUsdCost,
                    collectionMarginUsd, payoutMarginUsd,
                    sendAmount, serviceCharge,
                    collectionAmount, collectionCurrency,
                    offerRateColl, crossRate,
                    validUntil, isSameCcyShortCircuit);
        }
    }
}
