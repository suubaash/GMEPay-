package com.gme.pay.gateway.filter;

import com.gme.pay.gateway.replay.InMemoryNonceStore;
import com.gme.pay.gateway.replay.NonceStore;
import com.gme.pay.gateway.replay.ReplayProtectionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p><b>Shared nonce set.</b> The store is Redis-backed whenever one is configured
 * ({@link com.gme.pay.gateway.sharedstate.GatewaySharedStateConfig}), so a nonce burned on one
 * replica is burned for all of them. Before that, N replicas meant N permitted replays — the
 * control's guarantee scaled down with the deployment.
 *
 * <p><b>Store errors are answered, not propagated.</b> Previously the store could not fail (it was
 * a map) and nothing caught it; a Redis error would have become a bare 500 from the reactive
 * pipeline. Now it is 503 {@code REPLAY_STORE_UNAVAILABLE}, or a degraded per-JVM check under
 * {@code gateway.replay-protection.on-store-error=LOCAL}. There is no ALLOW — see
 * {@link ReplayProtectionProperties.OnStoreError}.
 */
@Component
public class ReplayProtectionFilter implements GlobalFilter, Ordered {

    /** Filter execution order: between HMAC (4) and idempotency (7). */
    public static final int ORDER = 5;

    /** Default nonce retention: the HMAC clock-skew window (5 min). */
    public static final long DEFAULT_NONCE_TTL_SECONDS = 300L;

    private static final Logger log = LoggerFactory.getLogger(ReplayProtectionFilter.class);

    private final NonceStore nonceStore;
    private final NonceStore localFallback;
    private final Duration nonceTtl;
    private final int maxNonceLength;
    private final ReplayProtectionProperties.OnStoreError onStoreError;

    @Autowired
    public ReplayProtectionFilter(NonceStore nonceStore,
                                  InMemoryNonceStore localFallback,
                                  ReplayProtectionProperties props) {
        this.nonceStore = nonceStore;
        this.localFallback = localFallback;
        this.nonceTtl = Duration.ofSeconds(props.getNonceTtlSeconds());
        this.maxNonceLength = props.getMaxNonceLength();
        this.onStoreError = props.getOnStoreError();
    }

    /**
     * No-fallback constructor for unit tests. {@code LOCAL} degrades to {@code REJECT} without a
     * fallback — the safe direction; production wiring always supplies one.
     */
    public ReplayProtectionFilter(NonceStore nonceStore, long nonceTtlSeconds) {
        this.nonceStore = nonceStore;
        this.localFallback = null;
        this.nonceTtl = Duration.ofSeconds(nonceTtlSeconds);
        this.maxNonceLength = new ReplayProtectionProperties().getMaxNonceLength();
        this.onStoreError = ReplayProtectionProperties.OnStoreError.REJECT;
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
        if (nonce.length() > maxNonceLength) {
            // A shared nonce set means a partner-supplied header sizes a write into shared
            // infrastructure. Bounded here rather than at the Redis client, so the rejection is a
            // documented 400 instead of an opaque store error.
            return GatewayErrorWriter.writeError(
                    exchange, HttpStatus.BAD_REQUEST, "INVALID_NONCE",
                    "X-Nonce must be at most " + maxNonceLength + " characters");
        }

        String pid = partnerId.toString();
        // The store outcome is materialised into an Outcome BEFORE the chain is invoked, so
        // onErrorResume covers the replay decision only. Scoping it any wider would swallow a
        // downstream error and write a replay verdict onto an already-committed response — the
        // same placement rule T0-7 established for the credential-resolving filters.
        return checkNonce(pid, nonce)
                .defaultIfEmpty(Outcome.UNAVAILABLE)
                .flatMap(outcome -> switch (outcome) {
                    case FRESH -> chain.filter(exchange);
                    case REPLAY -> {
                        log.warn("Replay detected for partner {} (nonce already used)", pid);
                        yield GatewayErrorWriter.writeError(
                                exchange, HttpStatus.UNAUTHORIZED, "REPLAY_DETECTED",
                                "Request nonce has already been used");
                    }
                    case UNAVAILABLE -> GatewayErrorWriter.writeError(
                            exchange, HttpStatus.SERVICE_UNAVAILABLE, "REPLAY_STORE_UNAVAILABLE",
                            "Replay protection is temporarily unavailable");
                });
    }

    /** The replay verdict, including "no verdict" — which must never read as "fresh". */
    private enum Outcome { FRESH, REPLAY, UNAVAILABLE }

    private Mono<Outcome> checkNonce(String partnerId, String nonce) {
        return nonceStore.checkAndSet(partnerId, nonce, nonceTtl)
                .map(ReplayProtectionFilter::toOutcome)
                .onErrorResume(err -> onStoreError(err, partnerId, nonce));
    }

    /**
     * Apply the configured posture for an unavailable nonce store. There is no ALLOW branch by
     * design: the only two answers are "no verdict, so no forwarding" and "fall back to the
     * per-JVM check", because a replay control that can be switched off by making one dependency
     * unreachable is not a control.
     */
    private Mono<Outcome> onStoreError(Throwable err, String partnerId, String nonce) {
        if (onStoreError == ReplayProtectionProperties.OnStoreError.LOCAL && localFallback != null) {
            log.warn("Nonce store error for partner {} — posture LOCAL, degrading to the per-JVM "
                    + "nonce set (a captured request becomes replayable once per replica): {}",
                    partnerId, err.toString());
            return localFallback.checkAndSet(partnerId, nonce, nonceTtl)
                    .map(ReplayProtectionFilter::toOutcome)
                    .onErrorReturn(Outcome.UNAVAILABLE)
                    .defaultIfEmpty(Outcome.UNAVAILABLE);
        }
        log.warn("Nonce store error for partner {} — posture REJECT, answering 503: {}",
                partnerId, err.toString());
        return Mono.just(Outcome.UNAVAILABLE);
    }

    private static Outcome toOutcome(Boolean fresh) {
        return Boolean.TRUE.equals(fresh) ? Outcome.FRESH : Outcome.REPLAY;
    }
}
