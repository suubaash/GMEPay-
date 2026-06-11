package com.gme.pay.registry.cache;

/**
 * Snapshot of the config-cache hit/miss counters, exposed via
 * {@code GET /v1/cache/stats} (this service has no actuator, so the counters are
 * served by a plain endpoint instead of a Micrometer registry).
 *
 * <p>{@code hitRatio} is a plain ratio in [0,1] — it is a metric, not money, so a
 * double is fine here (see MONEY_CONVENTION.md; monetary amounts stay BigDecimal).
 */
public record CacheStats(String backend, long hits, long misses, double hitRatio) {

    public static CacheStats of(String backend, long hits, long misses) {
        long total = hits + misses;
        double ratio = total == 0 ? 0.0 : (double) hits / total;
        return new CacheStats(backend, hits, misses, ratio);
    }
}
