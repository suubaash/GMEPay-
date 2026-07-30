package com.gme.pay.gateway.ratelimit;

import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory fixed-window {@link RateLimitStore} — <b>single-instance only</b>. The limit is
 * enforced per-JVM, so N replicas enforce N x the configured cap.
 *
 * <p>Two distinct roles, both wired in one place
 * ({@link com.gme.pay.gateway.sharedstate.GatewaySharedStateConfig}):
 * <ol>
 *   <li>the selected store when {@code gateway.shared-state.store} resolves to {@code memory}
 *       (local dev / tests / a deliberately single-replica deployment) — the config logs the
 *       resulting N=1 ceiling at startup rather than leaving it implicit; and</li>
 *   <li>the degraded fallback used by {@code on-store-error=LOCAL}, where the choice is not
 *       "shared or per-JVM" but "per-JVM or nothing".</li>
 * </ol>
 *
 * <p>This class carries no stereotype annotation on purpose. It used to be
 * {@code @Component @Primary @ConditionalOnProperty("gateway.rate-limit.store")} while nothing
 * ever set that property and no other implementation existed — a switch that looked like a
 * choice. Wiring now lives in exactly one class (the same correction T0-7 applied to
 * {@code ConfigPartnerCredentialService}).
 *
 * <p>Algorithm: each key maps to a {@link Window} holding the window-start epoch-millis and a
 * hit counter. A hit landing in a new window resets the counter; otherwise it increments.
 * The decision compares the post-increment count against the limit. Concurrency is handled
 * by computing atomically inside a single {@link ConcurrentHashMap#compute} mapping function.
 */
public class InMemoryRateLimitStore implements RateLimitStore {

    /** Sweep expired windows opportunistically once the map grows past this size. */
    private static final int SWEEP_THRESHOLD = 50_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryRateLimitStore() {
        this(Clock.systemUTC());
    }

    /** Visible for tests and for the shared-state config, which pins the fleet clock. */
    public InMemoryRateLimitStore(Clock clock) {
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
