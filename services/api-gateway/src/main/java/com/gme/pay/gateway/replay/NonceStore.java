package com.gme.pay.gateway.replay;

import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Replay-protection nonce store (port). A partner request carries a unique {@code X-Nonce}; the
 * gateway records it for the clock-skew window so an identical request replayed within that window
 * is rejected.
 *
 * <p>Production binds a Redis-backed implementation (atomic {@code SET key val NX EX ttl}) so the
 * nonce set is shared across all gateway instances. The default {@link InMemoryNonceStore} is a
 * single-instance fallback for local/dev (and tests) — it does NOT protect against replays hitting
 * a different instance, which is acceptable only without Redis.
 */
public interface NonceStore {

    /**
     * Atomically records {@code nonce} for {@code partnerId} if not already present within the TTL.
     *
     * @param partnerId the authenticated partner (replay scope is per-partner)
     * @param nonce     the partner-supplied unique value
     * @param ttl       how long to remember the nonce (typically the HMAC clock-skew window)
     * @return {@code true} if the nonce was fresh (first time seen → accept the request);
     *         {@code false} if it was already seen within the TTL (→ reject as a replay)
     */
    Mono<Boolean> checkAndSet(String partnerId, String nonce, Duration ttl);
}
