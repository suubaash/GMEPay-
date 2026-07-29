package com.gme.pay.gateway.registry;

import com.gme.pay.contracts.PartnerIpAllowlistView;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Fallback {@link ConfigRegistryClient} so the gateway boots standalone when no real registry is
 * wired. Replaced by {@link RestConfigRegistryClient} when {@code gmepay.config-registry.client=rest}
 * ({@code @Primary} + {@code @ConditionalOnProperty}).
 *
 * <p><b>T0-7 — it returns nothing, on purpose.</b> This class used to seed {@code partner_test_001}
 * (the partner whose api key and HMAC secret the deleted credential stub published) with SANDBOX
 * ranges covering loopback, the docker bridge and RFC1918 — i.e. the checked-in credential came with
 * a checked-in allowlist that admitted any caller on a private network. Since
 * {@code gmepay.config-registry.client} is set for this service in no deployment file, that seeded
 * allowlist was the live one everywhere.
 *
 * <p>An empty allowlist makes {@link com.gme.pay.gateway.filter.PartnerIpAllowlistFilter} answer
 * 403 {@code IP_NOT_ALLOWED} — the correct fail-closed posture for an environment whose registry is
 * not wired. It logs once at construction so the cause of a blanket 403 is discoverable from the
 * gateway's own startup log rather than from reading this class.
 */
@Component
public class StubConfigRegistryClient implements ConfigRegistryClient {

    private static final Logger log = LoggerFactory.getLogger(StubConfigRegistryClient.class);

    public StubConfigRegistryClient() {
        log.warn("No real config-registry client wired (gmepay.config-registry.client != rest): "
                + "every partner IP-allowlist lookup resolves to EMPTY, so partner requests are "
                + "answered 403 IP_NOT_ALLOWED. This is the T0-7 fail-closed default — set "
                + "gmepay.config-registry.client=rest to consult the real partner allowlist.");
    }

    @Override
    public Mono<List<PartnerIpAllowlistView>> getIpAllowlist(String partnerCode,
                                                             String environment) {
        // Deliberately no rows for any partner: see the class javadoc.
        return Mono.just(List.of());
    }
}
