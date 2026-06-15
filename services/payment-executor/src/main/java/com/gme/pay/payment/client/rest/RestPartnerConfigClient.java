package com.gme.pay.payment.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.payment.domain.PaymentException;
import com.gme.pay.payment.domain.client.PartnerConfigClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.RoundingMode;

/**
 * REST adapter that calls config-registry's {@code GET /v1/partners/{id}} endpoint
 * (Phase-1 persistence wiring, replaces in-memory stubs).
 *
 * <p>Base URL is read from {@code gmepay.config-registry.base-url} (default
 * {@code http://config-registry:8080}). On non-2xx the upstream error is rethrown as
 * {@link PaymentException} so the orchestrator surfaces a consistent failure type.
 */
@Component
@Primary
public class RestPartnerConfigClient implements PartnerConfigClient {

    private final RestClient restClient;

    @Autowired
    public RestPartnerConfigClient(
            RestClient.Builder builder,
            @Value("${gmepay.config-registry.base-url:http://config-registry:8080}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /** Package-private constructor used by tests that supply a pre-built RestClient. */
    RestPartnerConfigClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public PartnerConfigView loadPartner(String partnerId) {
        try {
            PartnerConfigResponse body = restClient.get()
                    .uri("/v1/partners/{id}", partnerId)
                    .retrieve()
                    .body(PartnerConfigResponse.class);

            if (body == null) {
                throw new PaymentException("config-registry returned empty body for partner " + partnerId);
            }
            return body.toView();
        } catch (RestClientResponseException ex) {
            throw new PaymentException(
                    "config-registry GET /v1/partners/" + partnerId + " failed: "
                            + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException(
                    "config-registry GET /v1/partners/" + partnerId + " failed: " + ex.getMessage(), ex);
        }
    }

    /** Wire format for {@code GET /v1/partners/{id}}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PartnerConfigResponse(
            String partnerId,
            String type,
            String settlementCurrency,
            String settlementRoundingMode
    ) {
        PartnerConfigView toView() {
            RoundingMode mode = settlementRoundingMode == null
                    ? RoundingMode.HALF_UP
                    : RoundingMode.valueOf(settlementRoundingMode);
            return new PartnerConfigView(partnerId, type, settlementCurrency, mode);
        }
    }
}
