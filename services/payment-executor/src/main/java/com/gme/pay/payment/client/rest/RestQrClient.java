package com.gme.pay.payment.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.payment.domain.PaymentException;
import com.gme.pay.payment.domain.client.QrClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * REST adapter that resolves a scanned QR token to merchant details by calling
 * merchant-qr-data at {@code GET /v1/merchants/{qr}}.
 *
 * <p>Base URL is read from {@code gmepay.merchant-qr-data.base-url} (default
 * {@code http://merchant-qr-data:8080}).
 */
@Component
@Primary
public class RestQrClient implements QrClient {

    private final RestClient restClient;

    public RestQrClient(
            RestClient.Builder builder,
            @Value("${gmepay.merchant-qr-data.base-url:http://merchant-qr-data:8080}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    RestQrClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public MerchantView resolve(String merchantQr) {
        try {
            MerchantResponse body = restClient.get()
                    .uri("/v1/merchants/{qr}", merchantQr)
                    .retrieve()
                    .body(MerchantResponse.class);

            if (body == null) {
                throw new PaymentException("merchant-qr-data returned empty body for qr " + merchantQr);
            }
            return new MerchantView(
                    body.merchantId(), body.merchantName(),
                    body.payoutCurrency(), body.schemeId());
        } catch (RestClientResponseException ex) {
            throw new PaymentException(
                    "merchant-qr-data GET /v1/merchants/" + merchantQr + " failed: "
                            + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException(
                    "merchant-qr-data GET /v1/merchants/" + merchantQr + " failed: " + ex.getMessage(), ex);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MerchantResponse(
            String merchantId,
            String merchantName,
            String payoutCurrency,
            String schemeId
    ) {}
}
