package com.gme.pay.txn.idempotency;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Redis-backed {@link IdempotencyStore} (ticket 17.3-G02).
 *
 * <p>Keys are namespaced {@code idem:{key}}. {@link #putIfAbsent(String, String)} maps to
 * a single atomic {@code SET key value NX EX <ttl>} (Spring Data's
 * {@code opsForValue().setIfAbsent(key, value, ttl)}), so under concurrent duplicates the
 * Redis server itself picks exactly one winner and the TTL is attached atomically — no
 * window where the key exists without an expiry.
 *
 * <p>Registered only when {@code spring.data.redis.host} is configured (docker-compose
 * exports {@code SPRING_DATA_REDIS_HOST}); otherwise the in-memory fallback is used.
 */
public class RedisIdempotencyStore implements IdempotencyStore {

    /** Redis key namespace: {@code idem:{client-key}}. */
    public static final String KEY_PREFIX = "idem:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisIdempotencyStore(StringRedisTemplate redis, Duration ttl) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive: " + ttl);
        }
    }

    @Override
    public Optional<String> putIfAbsent(String key, String responseSnapshot) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(responseSnapshot, "responseSnapshot");
        String namespaced = KEY_PREFIX + key;
        Boolean stored = redis.opsForValue().setIfAbsent(namespaced, responseSnapshot, ttl);
        if (Boolean.TRUE.equals(stored)) {
            return Optional.empty();
        }
        // Lost the SETNX race (or a snapshot already existed): replay the winner's snapshot.
        // If it expired in the gap between SETNX and GET (rare, key near end-of-TTL), treat
        // the caller as the winner of a fresh key.
        return Optional.ofNullable(redis.opsForValue().get(namespaced));
    }

    @Override
    public Optional<String> get(String key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(redis.opsForValue().get(KEY_PREFIX + key));
    }
}
