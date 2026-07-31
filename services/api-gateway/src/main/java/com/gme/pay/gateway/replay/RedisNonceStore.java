package com.gme.pay.gateway.replay;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;

/**
 * Redis-backed {@link NonceStore} — one atomic {@code SET key 1 NX EX ttl} per request.
 *
 * <p><b>Why this exists.</b> {@link InMemoryNonceStore} remembers nonces inside one JVM, so a
 * captured signed request could be replayed <em>once per replica</em> inside the HMAC clock-skew
 * window. That is an integrity defect, not a throughput one: replay protection that N replicas
 * turn into "N replays allowed" is not replay protection. With a shared store the first replica to
 * see a nonce burns it for all of them.
 *
 * <p>{@code SET NX} is the whole algorithm: Redis decides the winner server-side, and the TTL is
 * attached in the same command, so there is no window in which a nonce is recorded without an
 * expiry (which would permanently reject a legitimately-reused value after the skew window).
 *
 * <p><b>An empty reply is treated as NOT fresh.</b> Spring Data returns {@code Mono.empty()} when
 * the command produced no reply; "I do not know whether this nonce is new" must resolve to
 * "reject", because the alternative is accepting a replay on the strength of a missing answer.
 * Genuine transport failures error the {@link Mono} instead, and
 * {@link com.gme.pay.gateway.filter.ReplayProtectionFilter} applies the configured
 * {@code gateway.replay-protection.on-store-error} posture (default REJECT).
 */
public class RedisNonceStore implements NonceStore {

    /** Redis key namespace: {@code gw:nonce:{partnerId} {nonce}}. */
    public static final String KEY_PREFIX = "gw:nonce:";

    /** Stored value; only the key's existence carries meaning. */
    private static final String MARKER = "1";

    private final ReactiveStringRedisTemplate redis;

    public RedisNonceStore(ReactiveStringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    @Override
    public Mono<Boolean> checkAndSet(String partnerId, String nonce, Duration ttl) {
        Objects.requireNonNull(partnerId, "partnerId");
        Objects.requireNonNull(nonce, "nonce");
        // Same composition as InMemoryNonceStore: partnerId is server-derived (the credential
        // lookup produced it) and contains no space, so the separator cannot be forged from the
        // partner-supplied half.
        String key = KEY_PREFIX + partnerId + " " + nonce;
        return redis.opsForValue().setIfAbsent(key, MARKER, ttl)
                .defaultIfEmpty(Boolean.FALSE);
    }
}
