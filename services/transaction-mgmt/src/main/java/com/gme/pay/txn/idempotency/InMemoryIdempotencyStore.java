package com.gme.pay.txn.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-node, in-process {@link IdempotencyStore} — the default fallback when no Redis
 * host is configured (local dev, unit tests).
 *
 * <p>Win/lose semantics are decided atomically inside
 * {@link ConcurrentHashMap#compute(Object, java.util.function.BiFunction)} so concurrent
 * duplicates resolve to exactly one winner. TTL is enforced lazily: an expired entry is
 * treated as absent (and replaced on the next {@code putIfAbsent}).
 *
 * <p>The {@link Clock} is injectable for deterministic TTL tests.
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private record Entry(String snapshot, Instant expiresAt) {
        boolean isLiveAt(Instant now) {
            return expiresAt.isAfter(now);
        }
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;

    public InMemoryIdempotencyStore(Clock clock, Duration ttl) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive: " + ttl);
        }
    }

    @Override
    public Optional<String> putIfAbsent(String key, String responseSnapshot) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(responseSnapshot, "responseSnapshot");
        Instant now = clock.instant();
        AtomicBoolean won = new AtomicBoolean(false);
        Entry resolved = entries.compute(key, (k, existing) -> {
            if (existing == null || !existing.isLiveAt(now)) {
                won.set(true);
                return new Entry(responseSnapshot, now.plus(ttl));
            }
            return existing;
        });
        return won.get() ? Optional.empty() : Optional.of(resolved.snapshot());
    }

    @Override
    public Optional<String> get(String key) {
        Objects.requireNonNull(key, "key");
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.isLiveAt(clock.instant())) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.snapshot());
    }
}
