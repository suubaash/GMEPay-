package com.gme.pay.gateway.filter;

import com.gme.pay.gateway.replay.NonceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * GlobalFilter — replay protection via a per-partner nonce (API-05 §3.3 / WBS 8.2, 13.x).
 *
 * <p>Execution order: 5 (after {@link HmacSignatureFilter}=4, before {@link IdempotencyKeyFilter}=7).
 * Runs only for partner-authenticated requests: it reads the {@code partner_id} the HMAC filter
 * stored on the exchange, so non-partner traffic (e.g. human OIDC requests) passes straight through.
 *
 * <p>Algorithm: require an {@code X-Nonce} header; record it in the {@link NonceStore} for the
 * clock-skew window. A nonce seen twice within that window is a replay → 401 REPLAY_DETECTED. The
 * HMAC filter's timestamp window already bounds how long a captured request is replayable; this
 * filter closes the exact-replay gap inside that window.
 *
 * <p>The nonce is NOT part of the HMAC canonical string (the signature already binds method + path
 * + timestamp + body); the nonce only needs to be unique, not signed, to defeat exact replay.
 */
@Component
public class ReplayProtectionFilter implements GlobalFilter, Ordered {

    /** Filter execution order: between HMAC (4) and idempotency (7). */
    public static final int ORDER = 5;

    /** Default nonce retention: the HMAC clock-skew window (5 min). */
    public static final long DEFAULT_NONCE_TTL_SECONDS = 300L;

    private static final Logger log = LoggerFactory.getLogger(ReplayProtectionFilter.class);

    private final NonceStore nonceStore;
    private final Duration nonceTtl;

    public ReplayProtectionFilter(
            NonceStore nonceStore,
            @Value("${gateway.replay-protection.nonce-ttl-seconds:" + DEFAULT_NONCE_TTL_SECONDS + "}")
            long nonceTtlSeconds) {
        this.nonceStore = nonceStore;
        this.nonceTtl = Duration.ofSeconds(nonceTtlSeconds);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Object partnerId = exchange.getAttribute(HmacSignatureFilter.ATTR_PARTNER_ID);
        if (partnerId == null) {
            // Not an HMAC-authenticated partner request (e.g. human OIDC traffic) — replay N/A.
            return chain.filter(exchange);
        }

        String nonce = exchange.getRequest().getHeaders().getFirst("X-Nonce");
        if (nonce == null || nonce.isBlank()) {
            return GatewayErrorWriter.writeError(
                    exchange, HttpStatus.BAD_REQUEST, "MISSING_NONCE",
                    "X-Nonce header is required for signed partner requests");
        }

        return nonceStore.checkAndSet(partnerId.toString(), nonce, nonceTtl)
                .flatMap(fresh -> {
                    if (Boolean.TRUE.equals(fresh)) {
                        return chain.filter(exchange);
                    }
                    log.warn("Replay detected for partner {} (nonce already used)", partnerId);
                    return GatewayErrorWriter.writeError(
                            exchange, HttpStatus.UNAUTHORIZED, "REPLAY_DETECTED",
                            "Request nonce has already been used");
                });
    }
}
