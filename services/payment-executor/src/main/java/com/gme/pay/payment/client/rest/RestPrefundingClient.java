package com.gme.pay.payment.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.payment.domain.InsufficientPrefundingException;
import com.gme.pay.payment.domain.PaymentException;
import com.gme.pay.payment.domain.client.PrefundingClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;

/**
 * REST adapter that calls the Prefunding service for atomic deduct / reverse operations.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /v1/prefunding/{partner}/deduct}
 *   <li>{@code POST /v1/prefunding/{partner}/reverse}
 * </ul>
 *
 * <p>Base URL is read from {@code gmepay.prefunding.base-url} (default
 * {@code http://prefunding:8080}). A 402 Payment Required response is mapped to
 * {@link InsufficientPrefundingException} so the orchestrator can short-circuit before
 * touching the scheme.
 */
@Component
@Primary
public class RestPrefundingClient implements PrefundingClient {

    private final RestClient restClient;

    @Autowired
    public RestPrefundingClient(
            RestClient.Builder builder,
            @Value("${gmepay.prefunding.base-url:http://prefunding:8080}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    RestPrefundingClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public DeductionResult deduct(long partnerId, String txnRef, BigDecimal amountUsd) {
        try {
            DeductResponse body = restClient.post()
                    .uri("/v1/prefunding/{partner}/deduct", partnerId)
                    .body(new DeductRequest(txnRef, amountUsd))
                    .retrieve()
                    .body(DeductResponse.class);

            if (body == null) {
                throw new PaymentException("prefunding returned empty body for deduct " + txnRef);
            }
            return new DeductionResult(body.deductedUsd(), body.balanceAfter());
        } catch (RestClientResponseException ex) {
            HttpStatusCode status = ex.getStatusCode();
            if (status.value() == HttpStatus.PAYMENT_REQUIRED.value()) {
                throw new InsufficientPrefundingException(
                        nonNull(parseAvailable(ex)), nonNull(amountUsd));
            }
            throw new PaymentException(
                    "prefunding POST /v1/prefunding/" + partnerId + "/deduct failed: "
                            + status + " " + ex.getResponseBodyAsString(), ex);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException(
                    "prefunding POST /v1/prefunding/" + partnerId + "/deduct failed: "
                            + ex.getMessage(), ex);
        }
    }

    @Override
    public void reverse(long partnerId, String txnRef) {
        try {
            restClient.post()
                    .uri("/v1/prefunding/{partner}/reverse", partnerId)
                    .body(new ReverseRequest(txnRef))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new PaymentException(
                    "prefunding POST /v1/prefunding/" + partnerId + "/reverse failed: "
                            + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
        } catch (RuntimeException ex) {
            throw new PaymentException(
                    "prefunding POST /v1/prefunding/" + partnerId + "/reverse failed: "
                            + ex.getMessage(), ex);
        }
    }

    private static BigDecimal parseAvailable(RestClientResponseException ex) {
        // best-effort extraction; if absent we fall through to ZERO
        try {
            String body = ex.getResponseBodyAsString();
            if (body == null) return BigDecimal.ZERO;
            int idx = body.indexOf("\"available\"");
            if (idx < 0) return BigDecimal.ZERO;
            int colon = body.indexOf(':', idx);
            int end = body.indexOf(',', colon);
            if (end < 0) end = body.indexOf('}', colon);
            if (end < 0) return BigDecimal.ZERO;
            String raw = body.substring(colon + 1, end).trim().replace("\"", "");
            return new BigDecimal(raw);
        } catch (RuntimeException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal nonNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    record DeductRequest(String txnRef, BigDecimal amountUsd) {}

    record ReverseRequest(String txnRef) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DeductResponse(BigDecimal deductedUsd, BigDecimal balanceAfter) {}
}
