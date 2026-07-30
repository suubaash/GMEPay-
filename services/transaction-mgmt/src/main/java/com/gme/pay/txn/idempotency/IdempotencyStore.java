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
 * <p>Implementations: {@link JdbcIdempotencyStore} (the {@code idempotency_keys} table, V013 —
 * the production store, shared across replicas and durable) and {@link InMemoryIdempotencyStore}
 * (per-JVM; unit slices only, and it caps the service at one replica).
 *
 * <p><b>There is no other backstop.</b> This interface's javadoc used to claim "the DB unique
 * constraint on the transaction key remains the last-resort backstop". No such constraint exists —
 * {@code transactions} has no unique index on {@code partner_txn_ref} or on anything else a
 * duplicate would collide with (verified across V001-V012). This store is the only duplicate
 * suppression on the create path, which is why it is now durable and shared rather than a cache.
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
