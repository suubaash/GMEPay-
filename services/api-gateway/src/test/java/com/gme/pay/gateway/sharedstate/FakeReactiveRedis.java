package com.gme.pay.gateway.sharedstate;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * One in-process Redis shared by several {@link ReactiveStringRedisTemplate} handles.
 *
 * <p><b>Why a double and not Testcontainers.</b> This machine has no Docker (the repo's
 * Testcontainers tests are all {@code @Tag("docker")} and CI-only), and the property under test is
 * not "Lettuce speaks RESP" — it is <em>"two gateway instances pointed at the same store enforce
 * one combined limit and one nonce set"</em>. That property lives in the store classes' key
 * composition and window arithmetic, and it is provable against any backing map with faithful
 * {@code INCR} / {@code SET NX EX} / TTL semantics, which is what this provides. Two
 * {@link #template()} handles over one {@link #data} map are the two instances.
 *
 * <p><b>What it therefore does NOT prove:</b> the wire protocol, connection pooling, Lettuce
 * timeout behaviour, or Redis cluster key routing. Those need a real broker and are stated as
 * unverified rather than implied by a green test.
 *
 * <p>{@link #failWith(RuntimeException)} makes every subsequent command error, which is how the
 * documented store-unavailable postures are pinned.
 */
final class FakeReactiveRedis {

    private final Map<String, String> data = new ConcurrentHashMap<>();
    private final Map<String, Instant> expiry = new ConcurrentHashMap<>();
    private volatile Clock clock = Clock.systemUTC();
    private volatile RuntimeException fault;

    /** A fresh template handle over the same data — i.e. another replica. */
    @SuppressWarnings("unchecked")
    ReactiveStringRedisTemplate template() {
        ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> ops = mock(ReactiveValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);

        when(ops.increment(anyString()))
                .thenAnswer(inv -> guarded(() -> increment(inv.getArgument(0))));
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(inv -> guarded(() -> setIfAbsent(
                        inv.getArgument(0), inv.getArgument(1), inv.getArgument(2))));
        when(ops.get(anyString()))
                .thenAnswer(inv -> guarded(() -> read(inv.getArgument(0))));
        when(template.expire(anyString(), any(Duration.class)))
                .thenAnswer(inv -> guarded(() -> expire(inv.getArgument(0), inv.getArgument(1))));
        return template;
    }

    /** Make every command fail, as an unreachable Redis would. */
    void failWith(RuntimeException e) {
        this.fault = e;
    }

    void useClock(Clock clock) {
        this.clock = clock;
    }

    int keyCount() {
        reap();
        return data.size();
    }

    private <T> Mono<T> guarded(java.util.function.Supplier<T> op) {
        RuntimeException f = fault;
        if (f != null) {
            return Mono.error(f);
        }
        return Mono.fromCallable(op::get);
    }

    private synchronized Long increment(String key) {
        reap();
        long next = Long.parseLong(data.getOrDefault(key, "0")) + 1;
        data.put(key, Long.toString(next));
        return next;
    }

    private synchronized Boolean setIfAbsent(String key, String value, Duration ttl) {
        reap();
        if (data.containsKey(key)) {
            return Boolean.FALSE;
        }
        data.put(key, value);
        expiry.put(key, clock.instant().plus(ttl));
        return Boolean.TRUE;
    }

    private synchronized String read(String key) {
        reap();
        return data.get(key);
    }

    private synchronized Boolean expire(String key, Duration ttl) {
        if (!data.containsKey(key)) {
            return Boolean.FALSE;
        }
        expiry.put(key, clock.instant().plus(ttl));
        return Boolean.TRUE;
    }

    /** Redis expires lazily too; this keeps TTL semantics honest under a pinned clock. */
    private void reap() {
        Instant now = clock.instant();
        expiry.entrySet().removeIf(e -> {
            if (!e.getValue().isAfter(now)) {
                data.remove(e.getKey());
                return true;
            }
            return false;
        });
    }
}
