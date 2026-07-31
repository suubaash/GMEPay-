package com.gme.pay.txn.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-node, in-process {@link IdempotencyStore} — unit slices and local runs only. Selecting it
 * ({@code gmepay.idempotency.store=memory}) logs a {@code WARN} naming the N=1 ceiling it imposes: the
 * 24-hour replay window is per-JVM, so one partner retry landing on another replica creates a second
 * money transaction.
 *
 * <p>Implements the same claim-first protocol as {@link JdbcIdempotencyStore}, including the claim
 * lapse, so a test against this store exercises the same state machine the production store does.
 * Win/lose is decided atomically inside
 * {@link ConcurrentHashMap#compute(Object, java.util.function.BiFunction)}, which makes it correct
 * within one JVM and wrong across two — which is exactly the property the cross-replica tests pair
 * against.
 *
 * <p>The {@link Clock} is injectable for deterministic TTL tests.
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    /**
     * A key's state. {@code snapshot} is null while only claimed; {@code claimExpiresAt} is null once
     * completed.
     */
    private record Entry(String snapshot, Instant expiresAt, Instant claimExpiresAt) {

        boolean isLiveAt(Instant now) {
            return expiresAt.isAfter(now);
        }

        /** A claim nobody completed, held past its window — reclaimable (see V014's reasoning). */
        boolean isLapsedClaimAt(Instant now) {
            return snapshot == null && (claimExpiresAt == null || !claimExpiresAt.isAfter(now));
        }

        boolean isCompleted() {
            return snapshot != null;
        }
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;
    private final Duration claimTtl;

    public InMemoryIdempotencyStore(Clock clock, Duration ttl) {
        this(clock, ttl, JdbcIdempotencyStore.DEFAULT_CLAIM_TTL);
    }

    public InMemoryIdempotencyStore(Clock clock, Duration ttl, Duration claimTtl) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.claimTtl = Objects.requireNonNull(claimTtl, "claimTtl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive: " + ttl);
        }
        if (claimTtl.isNegative() || claimTtl.isZero()) {
            throw new IllegalArgumentException("claimTtl must be positive: " + claimTtl);
        }
    }

    @Override
    public Claim claim(String key) {
        Objects.requireNonNull(key, "key");
        Instant now = clock.instant();
        AtomicReference<Claim> outcome = new AtomicReference<>();
        entries.compute(key, (k, existing) -> {
            boolean reapable = existing == null
                    || !existing.isLiveAt(now)
                    || existing.isLapsedClaimAt(now);
            if (reapable) {
                outcome.set(Claim.claimed());
                return new Entry(null, now.plus(ttl), now.plus(claimTtl));
            }
            if (existing.isCompleted()) {
                outcome.set(Claim.replay(existing.snapshot()));
            } else {
                outcome.set(Claim.inFlight());
            }
            return existing;
        });
        return outcome.get();
    }

    @Override
    public void complete(String key, String responseSnapshot) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(responseSnapshot, "responseSnapshot");
        Instant now = clock.instant();
        entries.compute(key, (k, existing) -> {
            if (existing != null && existing.isCompleted()) {
                return existing; // someone already answered; first response wins
            }
            return new Entry(responseSnapshot, now.plus(ttl), null);
        });
    }

    @Override
    public void release(String key) {
        Objects.requireNonNull(key, "key");
        entries.compute(key, (k, existing) ->
                (existing == null || existing.isCompleted()) ? existing : null);
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
        // A claimed-but-unanswered key has no response to give.
        return Optional.ofNullable(entry.snapshot());
    }
}
