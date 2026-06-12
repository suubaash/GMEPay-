package com.gme.pay.bff.client.rest;

import com.gme.pay.bff.client.PrefundingClient;
import com.gme.pay.contracts.BalanceAlertView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Production {@link PrefundingClient} (Slice 5 — 5B.1). Talks to the prefunding
 * service over HTTP via Spring 6 {@link RestClient}. Active when
 * {@code gmepay.prefunding.client=rest}; otherwise the in-memory
 * {@link com.gme.pay.bff.client.stub.StubPrefundingClient} wins so the BFF still
 * boots standalone for tests / local dev — same convention as
 * {@link RestConfigRegistryClient}.
 *
 * <p>Endpoint mapping (prefunding/BalanceProvisioningController.java):
 * <ul>
 *   <li>{@code GET /v1/prefunding/{partnerCode}/balance} →
 *       {@link #getAdminBalance(String)} (binds the canonical {@link BalanceView}
 *       directly) and {@link #getBalance(String)} (legacy adapter shape).</li>
 *   <li>{@code GET /v1/prefunding/{partnerCode}/alerts} →
 *       {@link #getBalanceAlerts(String)}.</li>
 * </ul>
 *
 * <p>404 collapses to {@code null}/empty; network failures log + degrade the same
 * way so a prefunding outage never breaks the whole Admin partner page.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.prefunding.client", havingValue = "rest")
public class RestPrefundingClient implements PrefundingClient {

    private static final Logger log = LoggerFactory.getLogger(RestPrefundingClient.class);

    private final RestClient restClient;

    @Autowired
    public RestPrefundingClient(
            @Value("${gmepay.prefunding.base-url:http://prefunding:8080}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).build());
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestPrefundingClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public com.gme.pay.contracts.BalanceView getAdminBalance(String partnerCode) {
        try {
            return restClient.get()
                    .uri("/v1/prefunding/{partnerCode}/balance", partnerCode)
                    .retrieve()
                    .body(com.gme.pay.contracts.BalanceView.class);
        } catch (HttpClientErrorException.NotFound nf) {
            return null;
        } catch (ResourceAccessException network) {
            log.warn("prefunding unreachable on getAdminBalance({}): {}",
                    partnerCode, network.getMessage());
            return null;
        }
    }

    @Override
    public List<BalanceAlertView> getBalanceAlerts(String partnerCode) {
        try {
            List<BalanceAlertView> alerts = restClient.get()
                    .uri("/v1/prefunding/{partnerCode}/alerts", partnerCode)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<BalanceAlertView>>() {});
            return alerts == null ? List.of() : alerts;
        } catch (HttpClientErrorException.NotFound nf) {
            return List.of();
        } catch (ResourceAccessException network) {
            log.warn("prefunding unreachable on getBalanceAlerts({}): {}",
                    partnerCode, network.getMessage());
            return List.of();
        }
    }

    /** Legacy adapter shape used by the dashboard / portal controllers. */
    @Override
    public PrefundingClient.BalanceView getBalance(String partnerId) {
        com.gme.pay.contracts.BalanceView view = getAdminBalance(partnerId);
        if (view == null) {
            return null;
        }
        return new PrefundingClient.BalanceView(
                view.partnerCode(), view.currency(), view.balance(), view.threshold());
    }
}
