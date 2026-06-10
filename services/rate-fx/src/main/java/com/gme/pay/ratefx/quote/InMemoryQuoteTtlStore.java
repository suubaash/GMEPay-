package com.gme.pay.ratefx.quote;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default {@link QuoteTtlStore}: process-local map with lazy expiry. Used when
 * no Redis host is configured (dev boxes, unit slices). Locks do NOT survive a
 * service restart — production should configure {@code spring.data.redis.host}
 * to get the Redis-backed store instead.
 */
public final class InMemoryQuoteTtlStore implements QuoteTtlStore {

    private record Entry(StoredQuote quote, Instant deadline) {
    }

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryQuoteTtlStore() {
        this(Clock.systemUTC());
    }

    /** Clock-injecting constructor for deterministic expiry tests. */
    public InMemoryQuoteTtlStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void put(StoredQuote quote, Duration ttl) {
        QuoteTtlStore.requirePositive(ttl);
        entries.put(quote.quoteId(), new Entry(quote, clock.instant().plus(ttl)));
    }

    @Override
    public Optional<StoredQuote> find(String quoteId) {
        Entry entry = entries.get(quoteId);
        if (entry == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(entry.deadline())) {
            // Lazy eviction: same observable behaviour as a Redis EXPIRE.
            entries.remove(quoteId, entry);
            return Optional.empty();
        }
        return Optional.of(entry.quote());
    }

    @Override
    public void remove(String quoteId) {
        entries.remove(quoteId);
    }
}
