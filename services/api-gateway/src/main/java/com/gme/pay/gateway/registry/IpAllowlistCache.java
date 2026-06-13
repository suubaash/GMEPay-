package com.gme.pay.gateway.registry;

import com.gme.pay.contracts.PartnerIpAllowlistView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * TTL read-through cache in front of {@link ConfigRegistryClient#getIpAllowlist} — Slice 8.
 *
 * <p>The allowlist is consulted on EVERY partner request at the edge, so a per-request
 * round-trip to config-registry would put the registry on the gateway's hot path. A short
 * TTL (default 60 s, the slice ceiling) bounds staleness instead: an operator revoking a
 * CIDR knows the edge converges within a minute.
 *
 * <p>Implementation note: the gateway has no existing Caffeine usage (its only cache
 * patterns are reactive-Redis counters), so per the slice instruction this is a plain
 * {@link ConcurrentHashMap} with timestamped entries rather than a new external dependency.
 * Entries are only ever overwritten on refresh, never evicted — the key space is bounded by
 * (active partners x 2 environments), which is small by construction (V026 caps CIDRs, not
 * partners, but the partner roster is an operator-curated set).
 *
 * <p>Only successful lookups are cached. Errors propagate to the filter (whose
 * {@code fail-open} flag decides) and the next request retries the registry — negative
 * caching of outages would extend an outage's blast radius past the TTL.
 */
@Component
public class IpAllowlistCache {

    private record CacheEntry(List<String> cidrs, Instant fetchedAt) { }

    private final ConcurrentMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ConfigRegistryClient registryClient;
    private final Clock clock;
    private final Duration ttl;

    // Spring 6: with two constructors, @Autowired must mark the @Value one explicitly
    // (same trap RestConfigRegistryClient / RestAuditTrailClient hit earlier).
    @Autowired
    public IpAllowlistCache(
            ConfigRegistryClient registryClient,
            @Value("${security.gateway.allowlist.cache-ttl-seconds:60}") long ttlSeconds) {
        this(registryClient, Clock.systemUTC(), ttlSeconds);
    }

    /** Package-private constructor for tests to pin a deterministic clock. */
    IpAllowlistCache(ConfigRegistryClient registryClient, Clock clock, long ttlSeconds) {
        this.registryClient = registryClient;
        this.clock = clock;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /**
     * The CIDR strings allowed for (partnerCode, environment), served from cache while the
     * entry is younger than the TTL, refreshed from config-registry otherwise.
     */
    public Mono<List<String>> getCidrs(String partnerCode, String environment) {
        String key = partnerCode + "|" + environment;
        CacheEntry cached = cache.get(key);
        if (cached != null && isFresh(cached)) {
            return Mono.just(cached.cidrs());
        }
        return registryClient.getIpAllowlist(partnerCode, environment)
                .map(views -> views.stream().map(PartnerIpAllowlistView::cidr).toList())
                .doOnNext(cidrs -> cache.put(key, new CacheEntry(cidrs, clock.instant())));
    }

    private boolean isFresh(CacheEntry entry) {
        return entry.fetchedAt().plus(ttl).isAfter(clock.instant());
    }
}
