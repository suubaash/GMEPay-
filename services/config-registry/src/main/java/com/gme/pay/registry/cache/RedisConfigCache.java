package com.gme.pay.registry.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis-backed {@link ConfigCache}. Values are stored as JSON strings (serialized
 * with the application's {@link ObjectMapper}, so e.g. BigDecimal-as-string money
 * conventions carry over) under plain string keys with a fixed TTL
 * ({@code registry.cache.ttl}, default 10m).
 *
 * <p>Resilient by design: any Redis or (de)serialization failure is logged and
 * degraded — reads fall through to the DB as a miss, writes/evictions are dropped.
 * A dropped eviction means readers may see a stale value for at most one TTL,
 * which is why the TTL is kept short.
 *
 * <p>Hit/miss counters back the {@code GET /v1/cache/stats} endpoint (the service
 * carries no actuator/Micrometer registry, so plain {@link AtomicLong}s suffice).
 */
public class RedisConfigCache implements ConfigCache {

    private static final Logger log = LoggerFactory.getLogger(RedisConfigCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public RedisConfigCache(StringRedisTemplate redis, ObjectMapper objectMapper, Duration ttl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                misses.incrementAndGet();
                return Optional.empty();
            }
            T value = objectMapper.readValue(json, type);
            hits.incrementAndGet();
            return Optional.of(value);
        } catch (Exception e) {
            log.warn("config cache GET failed for key '{}', falling through to DB: {}", key, e.toString());
            misses.incrementAndGet();
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, Object value) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.warn("config cache PUT failed for key '{}' (value not cached): {}", key, e.toString());
        }
    }

    @Override
    public void evict(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("config cache DEL failed for key '{}' — readers may see a stale value for up to {}: {}",
                    key, ttl, e.toString());
        }
    }

    @Override
    public CacheStats stats() {
        return CacheStats.of("redis", hits.get(), misses.get());
    }
}
