package com.gme.pay.ratefx.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP impl of {@link PartnerConfigPort} — calls config-registry's
 * {@code GET /v1/partners/{code}} (currency split) and {@code GET /v1/partners/{code}/rules}
 * (pricing rules). Base URL from {@code gmepay.config-registry.base-url}
 * (default {@code http://config-registry:8080}). Mirrors {@code XeRateClient}: Spring 6 RestClient,
 * a {@link Value}-injected base URL, and a package-private test constructor taking a pre-built
 * {@link RestClient}.
 *
 * <p>The JSON is mapped into {@link PartnerConfigPort}'s local records (rate-fx does not depend on
 * lib-api-contracts). An upstream failure throws {@link ApiException} — quote issuance cannot
 * proceed without partner config.
 */
@Component
public class RestConfigRegistryClient implements PartnerConfigPort {

    private final RestClient restClient;

    @Autowired
    public RestConfigRegistryClient(
            @Value("${gmepay.config-registry.base-url:http://config-registry:8080}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** Test constructor — pre-built RestClient (e.g. backed by MockRestServiceServer). */
    public RestConfigRegistryClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public PartnerCurrencies getPartnerCurrencies(String partnerCode) {
        try {
            PartnerViewResponse v = restClient.get()
                    .uri("/v1/partners/{code}", partnerCode)
                    .retrieve()
                    .body(PartnerViewResponse.class);
            if (v == null) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "config-registry returned no partner '" + partnerCode + "'");
            }
            return new PartnerCurrencies(v.collectionCcy(), v.settleACcy(), v.settlementCurrency());
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "config-registry GET /v1/partners/" + partnerCode + " failed: " + e.getMessage());
        }
    }

    @Override
    public List<PartnerRule> getRules(String partnerCode) {
        try {
            List<RuleResponse> rules = restClient.get()
                    .uri("/v1/partners/{code}/rules", partnerCode)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<RuleResponse>>() {});
            if (rules == null) {
                return List.of();
            }
            return rules.stream()
                    .map(r -> new PartnerRule(r.schemeId(), r.direction(), r.mA(), r.mB(),
                            r.serviceChargeUsd(), r.rateCollSource(), r.ratePaySource()))
                    .toList();
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "config-registry GET /v1/partners/" + partnerCode + "/rules failed: "
                            + e.getMessage());
        }
    }

    /** Wire shape of config-registry's split-aware PartnerView (only the fields rate-fx needs). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PartnerViewResponse(String settlementCurrency, String collectionCcy, String settleACcy) {}

    /** Wire shape of config-registry's RuleView (money/margin as decimal strings → BigDecimal). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RuleResponse(String schemeId, String direction,
                        BigDecimal mA, BigDecimal mB, BigDecimal serviceChargeUsd,
                        String rateCollSource, String ratePaySource) {}
}
