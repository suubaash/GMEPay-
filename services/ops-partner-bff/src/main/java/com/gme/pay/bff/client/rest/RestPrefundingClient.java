package com.gme.pay.bff.client.rest;

import com.gme.pay.bff.client.PrefundingClient;
import com.gme.pay.contracts.BalanceAlertView;
import com.gme.pay.internalauth.InternalAuthHeaders;
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
 *
 * <p><b>Internal auth (T0-5 / T0-2):</b> prefunding's entire balance API — including the two
 * routes above — sits behind the service-to-service internal-auth gate
 * ({@code com.gme.pay.internalauth}), so the BFF presents the shared secret from
 * {@code gmepay.internal-auth.secret} in the {@code X-Gme-Internal} header. Without it the ops
 * partner-balance panel 401s against any correctly deployed prefunding (and, because a 401 is
 * neither a 404 nor a {@link ResourceAccessException}, it would surface as a hard error rather
 * than the intended graceful degradation). A blank secret sends no header and logs a WARN: a
 * gated prefunding then refuses the call, which is the fail-closed outcome of a missing
 * {@code GMEPAY_INTERNAL_AUTH_SECRET} rather than a fabricated credential.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.prefunding.client", havingValue = "rest")
public class RestPrefundingClient implements PrefundingClient {

    private static final Logger log = LoggerFactory.getLogger(RestPrefundingClient.class);

    private final RestClient restClient;

    @Autowired
    public RestPrefundingClient(
            @Value("${gmepay.prefunding.base-url:http://prefunding:8080}") String baseUrl,
            @Value("${gmepay.internal-auth.secret:}") String internalSecret) {
        this(builderFor(baseUrl, internalSecret).build());
    }

    /**
     * Builds the {@link RestClient.Builder} the production constructor uses: base URL plus, when a
     * secret is configured, the {@code X-Gme-Internal} default header. Package-private so a test can
     * bind a {@code MockRestServiceServer} to the very same builder and assert the header really
     * goes on the wire instead of trusting a hand-built client.
     */
    static RestClient.Builder builderFor(String baseUrl, String internalSecret) {
        RestClient.Builder b = RestClient.builder().baseUrl(baseUrl);
        if (internalSecret != null && !internalSecret.isBlank()) {
            b.defaultHeader(InternalAuthHeaders.INTERNAL_TOKEN, internalSecret);
        } else {
            log.warn("gmepay.internal-auth.secret is blank — calls to prefunding will carry no {} "
                    + "header and a gated prefunding will refuse them (401). Set "
                    + "GMEPAY_INTERNAL_AUTH_SECRET.", InternalAuthHeaders.INTERNAL_TOKEN);
        }
        return b;
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
