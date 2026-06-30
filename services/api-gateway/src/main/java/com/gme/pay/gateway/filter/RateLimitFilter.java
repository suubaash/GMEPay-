package com.gme.pay.gateway.filter;

import com.gme.pay.gateway.ratelimit.RateLimitProperties;
import com.gme.pay.gateway.ratelimit.RateLimitStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * and {@code X-RateLimit-Reset}. The backing {@link RateLimitStore} is Redis-optional; on a
 * store error the filter fails open or closed per {@code gateway.rate-limit.fail-open}.
 *
 * <p>Disabled by default ({@code gateway.rate-limit.enabled=false}); when disabled the filter
 * is a transparent pass-through so existing flows and tests are unaffected.
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

    public RateLimitFilter(RateLimitStore store, RateLimitProperties props) {
        this.store = store;
        this.props = props;
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
                .onErrorResume(err -> {
                    if (props.isFailOpen()) {
                        log.warn("Rate-limit store error for {} — failing open: {}", key,
                                err.toString());
                        // Synthesize an allow decision so the request proceeds with full headroom.
                        return Mono.just(new RateLimitStore.Decision(true, limit, limit, 0));
                    }
                    log.warn("Rate-limit store error for {} — failing closed: {}", key,
                            err.toString());
                    return Mono.just(new RateLimitStore.Decision(false, limit, 0,
                            WINDOW.toMillis()));
                })
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
