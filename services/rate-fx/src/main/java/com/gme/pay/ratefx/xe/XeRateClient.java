package com.gme.pay.ratefx.xe;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for the sim-rate-provider (xe.com simulator).
 *
 * <p>Active only when {@code gmepay.rate-fx.xe.enabled=true}.
 * Base URL is configurable via {@code gmepay.rate-fx.xe.base-url}
 * (default: http://localhost:9101).
 *
 * <p>Spring 6 rule: two constructors present — the @Value one is annotated
 * {@code @Autowired} so Spring selects it for injection.
 */
@Component
@ConditionalOnProperty(name = "gmepay.rate-fx.xe.enabled", havingValue = "true")
public class XeRateClient {

    private final RestClient restClient;

    /** Used by tests to supply a pre-built RestClient (e.g. backed by MockRestServiceServer). */
    XeRateClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Autowired
    public XeRateClient(
            @Value("${gmepay.rate-fx.xe.base-url:http://localhost:9101}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * Fetch all USD-base quotes from the simulator.
     *
     * @return parsed response containing a {@code quotes} map of ccy -> rate string
     */
    public XeMultiRateResponse fetchUsdRates() {
        return restClient.get()
                .uri("/v1/rates?base=USD")
                .retrieve()
                .body(XeMultiRateResponse.class);
    }
}
