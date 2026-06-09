package com.gme.pay.payment.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.payment.domain.PaymentException;
import com.gme.pay.payment.domain.client.RateClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * REST adapter that loads a previously issued rate quote from the rate-fx service
 * via {@code POST /v1/rates}.
 *
 * <p>Base URL is read from {@code gmepay.rate-fx.base-url} (default
 * {@code http://rate-fx:8080}).
 */
@Component
@Primary
public class RestRateClient implements RateClient {

    private final RestClient restClient;

    public RestRateClient(
            RestClient.Builder builder,
            @Value("${gmepay.rate-fx.base-url:http://rate-fx:8080}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    RestRateClient(RestClient restClient) {
        this.restClient = restClient;
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

    /** Wire format for the quote-lookup request body. */
    record QuoteLookupRequest(String quoteId, long partnerId) {}

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
