package com.gme.pay.gateway.registry;

import com.gme.pay.contracts.PartnerIpAllowlistView;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Gateway-internal contract for the config-registry lookups the edge needs — Slice 8
 * starts with the partner IP allowlist consulted by
 * {@link com.gme.pay.gateway.filter.PartnerIpAllowlistFilter}.
 *
 * <p>Same activation convention as the BFF's and prefunding's {@code ConfigRegistryClient}:
 * the in-memory {@link StubConfigRegistryClient} is the default so the gateway boots
 * standalone (tests, local dev); {@link RestConfigRegistryClient} takes over when
 * {@code gmepay.config-registry.client=rest} is set.
 *
 * <p>Reactive ({@link Mono}) because the gateway is a WebFlux stack — a blocking HTTP
 * call inside a {@code GlobalFilter} would stall a Netty event-loop thread.
 */
public interface ConfigRegistryClient {

    /**
     * The partner's IP allowlist rows for one credential environment.
     *
     * @param partnerCode the human-facing partner business code (never the BIGINT surrogate)
     * @param environment {@code SANDBOX} or {@code PRODUCTION} (V026 spelling)
     * @return the rows for that (partner, environment) — an EMPTY list when the partner is
     *         unknown or has no ranges registered (the filter fails closed on empty);
     *         {@link Mono#error} only on transport/5xx failures (the filter's fail-open
     *         flag decides what happens then).
     */
    Mono<List<PartnerIpAllowlistView>> getIpAllowlist(String partnerCode, String environment);
}
