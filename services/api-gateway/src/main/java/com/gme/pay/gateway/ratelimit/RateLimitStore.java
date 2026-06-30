package com.gme.pay.gateway.ratelimit;

import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Rate-limit accounting store (port). The gateway throttles partner traffic per
 * (partner_id, scope) using a fixed-window counter: every request increments the counter
 * for the current window; once the count exceeds the limit the request is rejected (429).
 *
 * <p>Production binds a Redis-backed implementation so the counter is shared across all
 * gateway instances (the limit is enforced globally, not per-pod). The default
 * {@link InMemoryRateLimitStore} is a single-instance fallback for local/dev and tests —
 * it enforces the limit only within one JVM, which is acceptable when no Redis is wired.
 *
 * <p>Same Redis-optional convention as {@link com.gme.pay.gateway.replay.NonceStore}: the
 * in-memory store is {@code @Primary} by default; a Redis store takes over when
 * {@code gateway.rate-limit.store=redis} is selected.
 */
public interface RateLimitStore {

    /**
     * Atomically count this hit inside the current fixed window and report the resulting state.
     *
     * @param key    the throttle key, typically {@code partnerId + ":" + scope}
     * @param limit  the maximum number of requests permitted within {@code window}
     * @param window the length of the fixed window (e.g. one second)
     * @return a {@link Decision} describing whether this request is allowed and the
     *         headroom/reset metadata used to populate the {@code X-RateLimit-*} headers
     */
    Mono<Decision> recordHit(String key, long limit, Duration window);

    /**
     * Outcome of a single {@link #recordHit} call.
     *
     * @param allowed         {@code true} when the request is within the limit
     * @param limit           the configured limit for this key
     * @param remaining       requests still permitted in the current window (never negative)
     * @param resetAfterMillis milliseconds until the current window resets (for Retry-After)
     */
    record Decision(boolean allowed, long limit, long remaining, long resetAfterMillis) {

        public long resetAfterSeconds() {
            // Round up so Retry-After never advises a retry before the window actually resets.
            return (resetAfterMillis + 999) / 1000;
        }
    }
}
