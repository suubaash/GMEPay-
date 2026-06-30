package com.gme.pay.gateway.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory fixed-window {@link RateLimitStore} — the default/fallback when no Redis is
 * configured (matches the gateway's other Redis-optional features). Single-instance only:
 * the limit is enforced per-JVM, so a multi-pod deployment should bind a Redis-backed store
 * ({@code gateway.rate-limit.store=redis}); this bean is {@code @Primary} only when no such
 * store is selected.
 *
 * <p>Algorithm: each key maps to a {@link Window} holding the window-start epoch-millis and a
 * hit counter. A hit landing in a new window resets the counter; otherwise it increments.
 * The decision compares the post-increment count against the limit. Concurrency is handled
 * by computing atomically inside a single {@link ConcurrentHashMap#compute} mapping function.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gateway.rate-limit.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryRateLimitStore implements RateLimitStore {

    /** Sweep expired windows opportunistically once the map grows past this size. */
    private static final int SWEEP_THRESHOLD = 50_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryRateLimitStore() {
        this(Clock.systemUTC());
    }

    /** Package-private constructor so tests can pin the clock. */
    InMemoryRateLimitStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Mono<Decision> recordHit(String key, long limit, Duration window) {
        return Mono.fromSupplier(() -> {
            long now = clock.millis();
            long windowMillis = window.toMillis();

            Window w = windows.compute(key, (k, existing) -> {
                if (existing == null || now - existing.startMillis >= windowMillis) {
                    return new Window(now, new AtomicLong(1));
                }
                existing.count.incrementAndGet();
                return existing;
            });

            long count = w.count.get();
            long elapsed = now - w.startMillis;
            long resetAfter = Math.max(0, windowMillis - elapsed);
            boolean allowed = count <= limit;
            long remaining = Math.max(0, limit - count);

            if (windows.size() > SWEEP_THRESHOLD) {
                windows.values().removeIf(v -> now - v.startMillis >= windowMillis);
            }
            return new Decision(allowed, limit, remaining, resetAfter);
        });
    }

    /** Mutable per-key fixed window. {@code count} is atomic so concurrent hits don't lose increments. */
    private static final class Window {
        private final long startMillis;
        private final AtomicLong count;

        private Window(long startMillis, AtomicLong count) {
            this.startMillis = startMillis;
            this.count = count;
        }
    }
}
