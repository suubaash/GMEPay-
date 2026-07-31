package com.gme.pay.gateway.filter;

import com.gme.pay.gateway.ratelimit.InMemoryRateLimitStore;
import com.gme.pay.gateway.ratelimit.RateLimitProperties;
import com.gme.pay.gateway.ratelimit.RateLimitStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/**
 * GlobalFilter — per-partner rate limiting (API-05 §3.5).
 *
 * <p>Execution order: 6 (after {@link ReplayProtectionFilter}=5, before
 * {@link IdempotencyKeyFilter}=7). Runs only for partner-authenticated requests: it keys the
 * counter off the {@code partner_id} the {@link HmacSignatureFilter} stored on the exchange,
 * so non-partner traffic (e.g. human OIDC requests) is never throttled here.
 *
 * <p>Limits (per partner, per second) come from {@link RateLimitProperties}: 100 global,
 * 20 for {@code POST /v1/rates}, 50 for {@code POST /v1/payments[/cpm/generate]}. The most
 * specific scope matching the original (pre-rewrite) request path wins.
 *
 * <p>On breach: 429 {@code RATE_LIMITED} with a {@code Retry-After} header. Every response
 * (allowed or rejected) carries {@code X-RateLimit-Limit}, {@code X-RateLimit-Remaining}
 * and {@code X-RateLimit-Reset}.
 *
 * <p><b>Store errors.</b> The counter now lives in Redis when one is configured
 * ({@link com.gme.pay.gateway.sharedstate.GatewaySharedStateConfig}), which makes the cap
 * fleet-wide and makes "Redis is down" a case this filter must answer. It answers it per
 * {@code gateway.rate-limit.on-store-error}: DENY (default, T0-7's fail-closed posture), LOCAL
 * (degrade to the per-JVM window — N x the cap, but still a cap) or ALLOW. The legacy
 * {@code fail-open: true} still means ALLOW. The reasoning behind each is on
 * {@link RateLimitProperties.OnStoreError}.
 *
 * <p>Enabled by default since T0-7 ({@code gateway.rate-limit.enabled=true}); when disabled the
 * filter is a transparent pass-through.
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    /** Filter execution order: between replay (5) and idempotency (7). */
    public static final int ORDER = 6;

    /** Scope label for the per-second window key. */
    private static final String SCOPE_GLOBAL = "global";
    private static final String SCOPE_RATES = "rates";
    private static final String SCOPE_PAYMENTS = "payments";

    private static final Duration WINDOW = Duration.ofSeconds(1);

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitStore store;
    private final RateLimitProperties props;
    /** Per-JVM window used only by {@code on-store-error=LOCAL}; may be null in unit tests. */
    private final RateLimitStore localFallback;

    @Autowired
    public RateLimitFilter(RateLimitStore store, RateLimitProperties props,
                           InMemoryRateLimitStore localFallback) {
        this.store = store;
        this.props = props;
        this.localFallback = localFallback;
    }

    /**
     * No-fallback constructor for unit tests. {@code on-store-error=LOCAL} degrades to DENY when
     * no fallback is present — the safe direction, and never the production wiring, which always
     * supplies one.
     */
    public RateLimitFilter(RateLimitStore store, RateLimitProperties props) {
        this(store, props, null);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!props.isEnabled()) {
            return chain.filter(exchange);
        }

        Object partnerId = exchange.getAttribute(HmacSignatureFilter.ATTR_PARTNER_ID);
        if (partnerId == null) {
            // Not an HMAC-authenticated partner request (e.g. human OIDC traffic) — not throttled.
            return chain.filter(exchange);
        }

        String scope = resolveScope(exchange);
        long limit = limitForScope(scope);
        String key = partnerId + ":" + scope;

        return store.recordHit(key, limit, WINDOW)
                .onErrorResume(err -> onStoreError(err, key, limit))
                .flatMap(decision -> {
                    writeRateLimitHeaders(exchange.getResponse(), decision);
                    if (decision.allowed()) {
                        return chain.filter(exchange);
                    }
                    log.warn("Rate limit exceeded for partner {} scope {} (limit {}/s)",
                            partnerId, scope, limit);
                    exchange.getResponse().getHeaders()
                            .set("Retry-After", Long.toString(decision.resetAfterSeconds()));
                    return GatewayErrorWriter.writeError(
                            exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                            "Per-partner rate limit of " + limit
                                    + " requests/second exceeded for scope '" + scope + "'");
                });
    }

    /**
     * Apply the configured posture for an unavailable store. Never propagates the error: the three
     * outcomes below are the only ones, so a Redis failure cannot reach the client as a bare 500.
     */
    private Mono<RateLimitStore.Decision> onStoreError(Throwable err, String key, long limit) {
        RateLimitProperties.OnStoreError posture = props.effectiveOnStoreError();
        switch (posture) {
            case ALLOW -> {
                log.warn("Rate-limit store error for {} — posture ALLOW, admitting unlimited "
                        + "traffic for the duration of the outage: {}", key, err.toString());
                return Mono.just(new RateLimitStore.Decision(true, limit, limit, 0));
            }
            case LOCAL -> {
                if (localFallback != null) {
                    log.warn("Rate-limit store error for {} — posture LOCAL, degrading to the "
                            + "per-JVM window (effective cap becomes replicas x {}): {}",
                            key, limit, err.toString());
                    return localFallback.recordHit(key, limit, WINDOW)
                            // The fallback is a map; if even that fails, deny.
                            .onErrorResume(fallbackErr -> denied(key, limit, fallbackErr));
                }
                log.warn("Rate-limit store error for {} — posture LOCAL but no local fallback is "
                        + "wired; denying: {}", key, err.toString());
                return denied(key, limit, err);
            }
            default -> {
                return denied(key, limit, err);
            }
        }
    }

    private Mono<RateLimitStore.Decision> denied(String key, long limit, Throwable err) {
        log.warn("Rate-limit store error for {} — posture DENY (T0-7 fail-closed): {}",
                key, err.toString());
        return Mono.just(new RateLimitStore.Decision(false, limit, 0, WINDOW.toMillis()));
    }

    private long limitForScope(String scope) {
        return switch (scope) {
            case SCOPE_RATES -> props.getRatesPerSecond();
            case SCOPE_PAYMENTS -> props.getPaymentsPerSecond();
            default -> props.getGlobalPerSecond();
        };
    }

    /**
     * Pick the most specific limit scope for this request from its original (pre-rewrite) path
     * and method. {@code POST /v1/rates} → rates; {@code POST /v1/payments} and
     * {@code POST /v1/payments/cpm/generate} → payments; everything else → global.
     */
    private String resolveScope(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        if (request.getMethod() != HttpMethod.POST) {
            return SCOPE_GLOBAL;
        }
        String path = originalRequestUri(exchange, request).getRawPath();
        if (path == null) {
            return SCOPE_GLOBAL;
        }
        if (path.equals("/v1/rates") || path.startsWith("/v1/rates/")) {
            return SCOPE_RATES;
        }
        if (path.equals("/v1/payments")
                || path.equals("/v1/payments/cpm/generate")) {
            return SCOPE_PAYMENTS;
        }
        return SCOPE_GLOBAL;
    }

    private void writeRateLimitHeaders(ServerHttpResponse response, RateLimitStore.Decision d) {
        HttpHeaders headers = response.getHeaders();
        headers.set("X-RateLimit-Limit", Long.toString(d.limit()));
        headers.set("X-RateLimit-Remaining", Long.toString(d.remaining()));
        headers.set("X-RateLimit-Reset", Long.toString(d.resetAfterSeconds()));
    }

    /**
     * The original, as-received request URI (before any gateway {@code RewritePath}), mirroring
     * {@link HmacSignatureFilter#originalRequestUri}. The partner addressed {@code /v1/...}; the
     * downstream-rewritten path would not match the scope rules.
     */
    private static URI originalRequestUri(ServerWebExchange exchange, ServerHttpRequest request) {
        Object attr = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR);
        if (attr instanceof Set<?> urls && !urls.isEmpty()) {
            Object first = urls.iterator().next();
            if (first instanceof URI uri) {
                return uri;
            }
        }
        return request.getURI();
    }
}
