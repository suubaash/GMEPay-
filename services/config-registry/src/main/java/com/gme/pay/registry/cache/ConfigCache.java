package com.gme.pay.registry.cache;

import java.util.Optional;

/**
 * Cache-aside facade for hot config reads (partners now; schemes/rules when they
 * gain persistent GET paths in 17.6-G01/G02). Readers try the cache first, fall
 * through to the DB on a miss and write the loaded value back; writers DEL the
 * affected keys after the DB write so the next read repopulates (<1s visibility).
 *
 * <p>Two implementations, selected by {@link CacheConfig}:
 * <ul>
 *   <li>{@link RedisConfigCache} when {@code spring.data.redis.host} is set;</li>
 *   <li>{@link NoOpConfigCache} otherwise — every read is a miss and writes are
 *       swallowed, so the code path degrades to a plain DB pass-through.</li>
 * </ul>
 */
public interface ConfigCache {

    /** Try the cache; empty on miss, on error, or when the cache is a no-op. */
    <T> Optional<T> get(String key, Class<T> type);

    /** Write the value back after a DB load. Best-effort; failures are swallowed. */
    void put(String key, Object value);

    /** DEL the key after a DB write so subsequent reads see the change immediately. */
    void evict(String key);

    /** Hit/miss counters (zeros for the no-op implementation). */
    CacheStats stats();
}
