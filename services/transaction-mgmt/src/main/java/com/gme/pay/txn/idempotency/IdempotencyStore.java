package com.gme.pay.txn.idempotency;

import java.util.Optional;

/**
 * Port for the idempotency-key store (ticket 17.3-G02).
 *
 * <p>Semantics required by the API contract:
 * <ul>
 *   <li><b>Exactly one winner</b> — for concurrent duplicate requests carrying the same
 *       {@code Idempotency-Key}, exactly one caller's {@link #putIfAbsent(String, String)}
 *       stores its response snapshot; every other caller observes the winner's snapshot.</li>
 *   <li><b>Replay</b> — later duplicates are answered with the first stored response
 *       snapshot, byte-for-byte identical body.</li>
 *   <li><b>24h TTL</b> — keys expire after {@code TTL} so a key may be legitimately reused
 *       the next day.</li>
 * </ul>
 *
 * <p>Implementations: {@link RedisIdempotencyStore} (Redis SETNX, production — activated
 * when {@code spring.data.redis.host} is set) and {@link InMemoryIdempotencyStore}
 * (single-node fallback / local default). The DB unique constraint on the transaction key
 * remains the last-resort backstop.
 */
public interface IdempotencyStore {

    /**
     * Atomically stores {@code responseSnapshot} under {@code key} unless a live snapshot
     * already exists.
     *
     * @param key              the client-supplied idempotency key (un-namespaced; the
     *                         implementation owns any storage prefix)
     * @param responseSnapshot the serialized first response to replay for duplicates
     * @return {@link Optional#empty()} if this call won (the snapshot was stored);
     *         the previously stored snapshot if a duplicate already won the key
     */
    Optional<String> putIfAbsent(String key, String responseSnapshot);

    /**
     * @return the stored response snapshot for {@code key}, or empty if the key is unknown
     *         or its TTL has elapsed
     */
    Optional<String> get(String key);
}
