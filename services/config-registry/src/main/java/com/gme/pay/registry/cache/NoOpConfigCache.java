package com.gme.pay.registry.cache;

import java.util.Optional;

/**
 * Pass-through {@link ConfigCache} used when {@code spring.data.redis.host} is not
 * configured (local dev, unit slices). Every read misses, writes and evictions do
 * nothing, and the counters stay at zero — callers behave exactly as if there were
 * no cache layer at all.
 */
public class NoOpConfigCache implements ConfigCache {

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        return Optional.empty();
    }

    @Override
    public void put(String key, Object value) {
        // no-op
    }

    @Override
    public void evict(String key) {
        // no-op
    }

    @Override
    public CacheStats stats() {
        return CacheStats.of("noop", 0, 0);
    }
}
