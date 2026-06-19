package com.gme.pay.gateway.filter;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Calls auth-identity {@code POST /v1/rbac/resolve} to obtain a principal's effective
 * permissions, with a short-TTL in-JVM cache so the resolve happens at most once per
 * principal per TTL — keeping the edge fast (the &lt;50ms NFR). Fail-open: any error or
 * miss yields {@link Mono#empty()}, so the gateway still routes (downstream denies).
 *
 * <p>Active only when {@code gmepay.rbac.stamp.enabled=true}, so the gateway's default
 * behaviour is unchanged until RBAC stamping is switched on.
 */
@Component
@ConditionalOnProperty(prefix = "gmepay.rbac.stamp", name = "enabled", havingValue = "true")
public class WebClientRbacClaimResolver implements RbacClaimResolver {

    private static final Logger log = LoggerFactory.getLogger(WebClientRbacClaimResolver.class);
    private static final Duration TTL = Duration.ofSeconds(60);

    private final WebClient client;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public WebClientRbacClaimResolver(
            WebClient.Builder builder,
            @Value("${gme.auth-identity.base-url:http://auth-identity:8080}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
    }

    @Override
    public Mono<RbacClaims> resolve(String username) {
        Cached c = cache.get(username);
        if (c != null && c.expiresAt.isAfter(Instant.now())) {
            return Mono.just(c.claims);
        }
        return client.post()
                .uri("/v1/rbac/resolve")
                .bodyValue(Map.of("username", username))
                .retrieve()
                .bodyToMono(ResolveResponse.class)
                .map(WebClientRbacClaimResolver::toClaims)
                .doOnNext(claims -> cache.put(username, new Cached(claims, Instant.now().plus(TTL))))
                .onErrorResume(e -> {
                    log.warn("rbac resolve failed for {}: {} — passing through unstamped", username, e.toString());
                    return Mono.empty();
                });
    }

    private static RbacClaims toClaims(ResolveResponse r) {
        return new RbacClaims(
                r.principalId() == null ? null : String.valueOf(r.principalId()),
                r.tenantId(),
                r.permissions() == null ? List.of() : r.permissions(),
                r.roles() == null ? List.of() : r.roles(),
                r.constraints() == null ? "" : r.constraints());
    }

    /** Mirrors auth-identity's ResolvedAccess JSON. */
    private record ResolveResponse(Long principalId, String username, String tenantId,
                                   List<String> roles, List<String> permissions, String constraints) {}

    private record Cached(RbacClaims claims, Instant expiresAt) {}
}
