package com.gme.pay.gateway.registry;

import com.gme.pay.contracts.PartnerIpAllowlistView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Default in-memory {@link ConfigRegistryClient} so the gateway boots standalone for
 * tests and local dev — the same role {@link com.gme.pay.gateway.partner.StubPartnerCredentialService}
 * plays for credentials. Replaced by {@link RestConfigRegistryClient} when
 * {@code gmepay.config-registry.client=rest} ({@code @Primary} + {@code @ConditionalOnProperty}).
 *
 * <p>Seeds the same test partner the credential stub registers ({@code partner_test_001},
 * API key {@code pk_test_abc}) with SANDBOX ranges that cover local callers (loopback,
 * RFC1918) so the filter chain is exercisable end-to-end on a dev box. Its PRODUCTION
 * allowlist is deliberately EMPTY: production traffic for the stub partner is rejected,
 * which is the fail-closed posture the Slice 8 contract demands for an unconfigured
 * environment.
 */
@Component
public class StubConfigRegistryClient implements ConfigRegistryClient {

    private static final Instant SEEDED_AT = Instant.parse("2026-06-01T00:00:00Z");

    private static final Map<String, List<PartnerIpAllowlistView>> STORE = Map.of(
            "partner_test_001", List.of(
                    entry(1L, "127.0.0.0/8", "local loopback", "SANDBOX"),
                    entry(2L, "::1/128", "local loopback (v6)", "SANDBOX"),
                    entry(3L, "10.0.0.0/8", "docker-compose bridge", "SANDBOX"),
                    entry(4L, "192.168.0.0/16", "dev LAN", "SANDBOX")));

    @Override
    public Mono<List<PartnerIpAllowlistView>> getIpAllowlist(String partnerCode,
                                                             String environment) {
        return Mono.just(STORE.getOrDefault(partnerCode, List.of()).stream()
                .filter(v -> v.environment().equalsIgnoreCase(environment))
                .toList());
    }

    private static PartnerIpAllowlistView entry(Long id, String cidr, String label,
                                                String environment) {
        return new PartnerIpAllowlistView(id, cidr, label, environment, SEEDED_AT, "stub");
    }
}
