package com.gme.pay.bff.alert.paging;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-JVM {@link PagingCooldown}. Two roles, both wired in
 * {@link PagingCooldownConfig}: the selected cooldown when no Redis is configured, and the
 * fallback {@link FailoverPagingCooldown} uses when Redis is unreachable.
 *
 * <p>At N&gt;1 replicas this deduplicates only within one pod, which is the defect the Redis
 * implementation exists to fix. It is still correct at N=1 and it is still the right thing to
 * degrade to, because the alternative when the shared cooldown is unavailable is no dedupe at all.
 */
public class InMemoryPagingCooldown implements PagingCooldown {

    /** Opportunistic sweep threshold; the key space is (alertType|subjectRef), so it is small. */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final Map<String, Instant> held = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryPagingCooldown() {
        this(Clock.systemUTC());
    }

    public InMemoryPagingCooldown(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean tryClaim(String key, Duration window) {
        Instant now = clock.instant();
        Instant expiry = now.plus(window);
        // Same merge idiom as the gateway's nonce store: keep a still-valid holder, otherwise
        // install ours. Reference identity says which branch won, so the decision is atomic.
        Instant winner = held.merge(key, expiry,
                (existing, candidate) -> existing.isAfter(now) ? existing : candidate);
        boolean claimed = winner == expiry;
        if (held.size() > SWEEP_THRESHOLD) {
            held.values().removeIf(e -> !e.isAfter(now));
        }
        return claimed;
    }

    @Override
    public void release(String key) {
        held.remove(key);
    }
}
