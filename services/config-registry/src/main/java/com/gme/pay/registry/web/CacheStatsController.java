package com.gme.pay.registry.web;

import com.gme.pay.registry.cache.CacheStats;
import com.gme.pay.registry.cache.ConfigCache;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the config-cache hit/miss counters (ticket 17.3-G03).
 * This service has no actuator on the classpath, so the counters are exposed via
 * a plain endpoint instead of a Micrometer registry. Reports backend "noop" with
 * zero counters when Redis is not configured.
 */
@RestController
@RequestMapping("/v1/cache")
public class CacheStatsController {

    private final ConfigCache cache;

    public CacheStatsController(ConfigCache cache) {
        this.cache = cache;
    }

    @GetMapping("/stats")
    public CacheStats stats() {
        return cache.stats();
    }
}
