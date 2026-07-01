package com.gme.pay.payment.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.payment.domain.PaymentException;
import com.gme.pay.payment.domain.client.SmartRouterClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * REST {@link SmartRouterClient} that resolves candidates from the {@code smart-router} service
 * (ADR-016 §2–3):
 * <pre>GET {base}/v1/route/resolve?network=&amp;country=&amp;mode=&amp;direction=</pre>
 *
 * <p>Gated on {@code gmepay.smart-router.base-url} via {@code @ConditionalOnProperty}: when the
 * base-url is absent this bean is not created and the in-process {@link FixtureSmartRouterClient}
 * takes over (its {@code @ConditionalOnMissingBean(RestSmartRouterClient.class)} guard).
 *
 * <p>The service returns candidate rows carrying config-registry's {@code schemeId}; each row's
 * scheme code is mapped straight through to the code {@link SchemeClientRouter} dispatches on.
 * Candidates are sorted by ascending {@code priority} (defensive — the service already orders
 * them). A resolve failure surfaces an empty list (caller → SCHEME_UNAVAILABLE) rather than
 * throwing, so a smart-router outage degrades cleanly.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "gmepay.smart-router", name = "base-url")
public class RestSmartRouterClient implements SmartRouterClient {

    private static final Logger log = LoggerFactory.getLogger(RestSmartRouterClient.class);

    private final RestClient restClient;

    @Autowired
    public RestSmartRouterClient(
            RestClient.Builder builder,
            @Value("${gmepay.smart-router.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    RestSmartRouterClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public List<PartnerSchemeView> resolve(String network, String country, String mode, String direction) {
        try {
            String uri = UriComponentsBuilder.fromPath("/v1/route/resolve")
                    .queryParam("network", network)
                    .queryParamIfPresent("country", java.util.Optional.ofNullable(country))
                    .queryParam("mode", mode)
                    .queryParam("direction", direction)
                    .build()
                    .toUriString();

            ResolveResponse body = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(ResolveResponse.class);

            if (body == null || body.candidates() == null) {
                return List.of();
            }
            List<PartnerSchemeView> views = new ArrayList<>();
            for (Candidate c : body.candidates()) {
                views.add(new PartnerSchemeView(
                        c.partnerId(),
                        c.partnerName(),
                        c.schemeId(),
                        c.priority()));
            }
            views.sort(Comparator.comparingInt(PartnerSchemeView::priority));
            return views;
        } catch (RestClientException ex) {
            // Resolve is best-effort context: a smart-router outage yields no candidates and the
            // caller returns SCHEME_UNAVAILABLE, rather than bubbling a 500 to the wallet.
            log.warn("smart-router resolve failed (network={}, country={}): {}",
                    network, country, ex.getMessage());
            return List.of();
        } catch (RuntimeException ex) {
            throw new PaymentException("smart-router resolve failed: " + ex.getMessage(), ex);
        }
    }

    // ---- wire formats (smart-router /v1/route/resolve contract) ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResolveResponse(List<Candidate> candidates) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Candidate(long partnerId, String partnerName, String schemeId, int priority) {
    }
}
