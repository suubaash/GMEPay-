package com.gme.pay.gateway.ratelimit;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Redis-backed fixed-window {@link RateLimitStore} — the implementation that makes the
 * per-partner cap a <b>fleet-wide</b> cap instead of a per-JVM one.
 *
 * <p><b>Why this exists.</b> {@link InMemoryRateLimitStore} counts inside one JVM, so N gateway
 * replicas enforce N x the configured limit. That is not a scaling inconvenience, it is a
 * <em>wrong control</em>: the cap exists to bound credential-stuffing and enumeration at the edge,
 * and an attacker spreading traffic across replicas gets N times the budget the operator
 * configured. T0-7 turned the cap on and made it fail closed; this class is what makes the number
 * it enforces the number that was configured.
 *
 * <h2>Algorithm — window-indexed INCR</h2>
 * <pre>
 *   index    = floor(now / windowMillis)          // aligned, so every replica agrees
 *   key      = gw:rl:{throttleKey}:{index}
 *   count    = INCR key                            // atomic on the server; no read-modify-write
 *   if count == 1 -&gt; EXPIRE key (2 x window)       // reap; correctness does not depend on it
 * </pre>
 *
 * <p>Two properties are deliberate:
 *
 * <ul>
 *   <li><b>The window index is part of the key.</b> A fixed window whose start is stored in the
 *       value would need a read-modify-write and therefore a Lua script or a lock. Putting the
 *       window number in the key makes {@code INCR} — a single atomic server-side operation —
 *       sufficient, and it makes every replica agree on where the window boundary is without any
 *       clock coordination beyond the ordinary NTP skew they already need.</li>
 *   <li><b>A failed {@code EXPIRE} cannot break the limit, only leak a key.</b> Because the key
 *       changes every window, a key that never got its TTL stops being consulted the moment the
 *       window rolls; it does not pin a partner at its limit forever. That is the failure mode
 *       worth designing away, and the reason this is not "INCR then hope".</li>
 * </ul>
 *
 * <p><b>Clock skew between replicas</b> shifts a partner's window boundary by the skew, which at
 * worst lets a burst straddle two windows — the standard fixed-window property, unchanged by
 * sharding. It does not multiply the cap.
 *
 * <p><b>Errors are propagated, never swallowed.</b> A Redis failure surfaces as an errored
 * {@link Mono} so {@link com.gme.pay.gateway.filter.RateLimitFilter} applies the configured
 * {@code gateway.rate-limit.on-store-error} posture (default DENY — T0-7's fail-closed default).
 * Silently synthesising an "allowed" decision here would hide the outage behind the exact outcome
 * an attacker wants.
 */
public class RedisRateLimitStore implements RateLimitStore {

    /** Redis key namespace: {@code gw:rl:{partnerId}:{scope}:{windowIndex}}. */
    public static final String KEY_PREFIX = "gw:rl:";

    private final ReactiveStringRedisTemplate redis;
    private final Clock clock;

    public RedisRateLimitStore(ReactiveStringRedisTemplate redis) {
        this(redis, Clock.systemUTC());
    }

    /** Visible for tests, which pin the clock to make window rollover deterministic. */
    public RedisRateLimitStore(ReactiveStringRedisTemplate redis, Clock clock) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Mono<Decision> recordHit(String key, long limit, Duration window) {
        long windowMillis = window.toMillis();
        if (windowMillis <= 0) {
            return Mono.error(new IllegalArgumentException("window must be positive: " + window));
        }
        long now = clock.millis();
        long index = now / windowMillis;
        String redisKey = KEY_PREFIX + key + ":" + index;
        long resetAfterMillis = ((index + 1) * windowMillis) - now;
        // 2x the window: long enough that a key is never reaped while still being counted against,
        // short enough that an idle partner leaves nothing behind.
        Duration ttl = Duration.ofMillis(windowMillis * 2);

        return redis.opsForValue().increment(redisKey)
                .flatMap(count -> count != null && count == 1L
                        ? redis.expire(redisKey, ttl).thenReturn(count)
                        : Mono.just(count))
                // INCR always returns the new value; an empty Mono would mean the command did not
                // run, which is a store failure and must not read as "count 0, therefore allowed".
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Redis INCR returned no value for " + redisKey)))
                .map(count -> new Decision(
                        count <= limit, limit, Math.max(0, limit - count), resetAfterMillis));
    }
}
