package com.gme.pay.gateway.registry;

import com.gme.pay.contracts.PartnerIpAllowlistView;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Production {@link ConfigRegistryClient} for the gateway. Talks to config-registry's
 * {@code GET /v1/admin/partners/{partnerCode}/ip-allowlist} (PartnerIpAllowlistController,
 * Slice 8 Lane B) and filters the both-environments payload down to the requested one.
 *
 * <p>Uses reactive {@link WebClient} rather than the {@code RestClient} the BFF/prefunding
 * clients use because the gateway is a WebFlux stack — the call happens inside a
 * {@code GlobalFilter} on a Netty event loop where blocking I/O is forbidden.
 *
 * <p>Active when {@code gmepay.config-registry.client=rest}; otherwise the in-memory
 * {@link StubConfigRegistryClient} wins (same convention as the BFF's
 * RestConfigRegistryClient).
 *
 * <p>Error mapping:
 * <ul>
 *   <li>404 (unknown partner) — collapses to an empty list: the filter then fails CLOSED
 *       (403 IP_NOT_ALLOWED), which is correct for a partner code we cannot vouch for.</li>
 *   <li>Transport errors / 5xx — propagated as {@link Mono#error}: the filter's
 *       {@code security.gateway.allowlist.fail-open} flag decides (mirrors the
 *       {@code gateway.replay-protection.fail-open} precedent).</li>
 * </ul>
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.config-registry.client", havingValue = "rest")
public class RestConfigRegistryClient implements ConfigRegistryClient {

    private static final Logger log = LoggerFactory.getLogger(RestConfigRegistryClient.class);

    private final WebClient webClient;

    // Spring 6: with two constructors, @Autowired must mark the @Value one explicitly
    // (same trap RestAuditTrailClient / the BFF RestConfigRegistryClient hit earlier).
    @Autowired
    public RestConfigRegistryClient(
            WebClient.Builder builder,
            @Value("${gmepay.config-registry.base-url:http://config-registry:8080}") String baseUrl) {
        // T3-11 defect 1, reactive half: this was the STATIC WebClient.builder(), which returns a fresh
        // builder that no WebClientCustomizer has touched — so lib-errors'
        // WebClientTimeoutAutoConfiguration never reached it and this call had no connect timeout and no
        // response timeout of any kind. `builder` is the injected BEAN and carries both.
        //
        // The consequence here is worse than a slow request. This runs inside a GlobalFilter on a Netty
        // EVENT LOOP, and the IP-allowlist decision gates an authenticated partner request. Unbounded,
        // an unresponsive config-registry did not fail open or closed — the fail-open flag below never
        // got to decide, because the Mono never terminated, so the request neither completed nor was
        // rejected and the loop slot was held.
        this(builder.baseUrl(baseUrl).build());
    }

    /** Package-private constructor for tests to inject a pre-built WebClient. */
    RestConfigRegistryClient(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Mono<List<PartnerIpAllowlistView>> getIpAllowlist(String partnerCode,
                                                             String environment) {
        return webClient.get()
                .uri("/v1/admin/partners/{partnerCode}/ip-allowlist", partnerCode)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToFlux(PartnerIpAllowlistView.class)
                .filter(v -> v.environment() != null
                        && v.environment().equalsIgnoreCase(environment))
                .collectList()
                .onErrorResume(WebClientResponseException.NotFound.class, notFound -> {
                    // Unknown partner code: empty allowlist => the filter fails closed.
                    log.warn("config-registry has no partner '{}' — treating allowlist as empty",
                            partnerCode);
                    return Mono.just(List.of());
                });
    }
}
