package com.gme.pay.txn.idempotency;

import java.util.Optional;

/**
 * Port for the idempotency-key store (ticket 17.3-G02).
 *
 * <h2>Claim first, create second</h2>
 * The protocol is deliberately three calls rather than one {@code putIfAbsent}, because the store has
 * to be consulted <b>before</b> the transaction exists. The previous single-call shape forced the
 * caller to create first and claim afterwards, and two simultaneous requests carrying the same key
 * therefore created <b>two transactions</b> — one of them orphaned, with a {@code txn_ref} no caller
 * ever saw. Sequential retries were always fine; the concurrent window was not, and that is precisely
 * the window an idempotency key exists for.
 *
 * <pre>
 *   Claim c = store.claim(key);
 *   switch (c.outcome()) {
 *     case REPLAY    -> return 200 with c.snapshot();      // a previous request already answered
 *     case IN_FLIGHT -> return 409 IDEMPOTENCY_CONFLICT;    // a duplicate is being served right now
 *     case CLAIMED   -> {                                   // we own the key
 *         try { response = create(); }
 *         catch (RuntimeException e) { store.release(key); throw e; }
 *         store.complete(key, snapshot(response));
 *         return 201;
 *     }
 *   }
 * </pre>
 *
 * <h2>Semantics required by the API contract</h2>
 * <ul>
 *   <li><b>Exactly one winner</b> — for concurrent duplicates, exactly one caller receives
 *       {@link Outcome#CLAIMED}; the others get {@link Outcome#IN_FLIGHT} or, once the winner has
 *       finished, {@link Outcome#REPLAY}.</li>
 *   <li><b>Replay</b> — later duplicates are answered with the first stored response snapshot,
 *       byte-for-byte identical body.</li>
 *   <li><b>A duplicate must never become a LOST payment.</b> {@link Outcome#IN_FLIGHT} is a
 *       <em>retryable</em> answer: the same key retried later replays the winner's response. A claim
 *       whose holder dies must lapse (see the implementations' claim TTL) so a payment is never
 *       permanently un-creatable — the failure mode that would be worse than the duplicate this
 *       protocol prevents.</li>
 *   <li><b>24h TTL</b> — a completed key expires after the replay window so it may be legitimately
 *       reused the next day.</li>
 * </ul>
 *
 * <p>Implementations: {@link JdbcIdempotencyStore} (the {@code idempotency_keys} table, V013 + V014 —
 * the production store, shared across replicas and durable) and {@link InMemoryIdempotencyStore}
 * (per-JVM; unit slices only, and it caps the service at one replica).
 *
 * <p><b>The other backstop, and what it does and does not cover.</b> This interface's javadoc once
 * claimed "the DB unique constraint on the transaction key remains the last-resort backstop" when no
 * such constraint existed. There is one now — {@code ux_transactions_partner_txn_ref} on
 * {@code (partner_id, partner_txn_ref)}, V015 — and it matters for two cases this store cannot cover:
 * payment-executor's internal {@code createPending} sends <b>no</b> {@code Idempotency-Key} at all
 * (only the partner edge enforces the header), and a claim that lapsed because its holder died can be
 * taken over by a retry that then creates a second row. Neither store nor index is sufficient alone.
 */
public interface IdempotencyStore {

    /** What {@link #claim} decided. */
    enum Outcome {
        /** The caller owns the key and must now create, then {@link #complete} or {@link #release}. */
        CLAIMED,
        /** Another caller holds the claim and has not answered yet. Retryable with the same key. */
        IN_FLIGHT,
        /** A previous request already answered; replay its snapshot. */
        REPLAY
    }

    /**
     * The result of a claim attempt. {@code snapshot} is non-null only for {@link Outcome#REPLAY}.
     */
    record Claim(Outcome outcome, String snapshot) {

        public static Claim claimed() {
            return new Claim(Outcome.CLAIMED, null);
        }

        public static Claim inFlight() {
            return new Claim(Outcome.IN_FLIGHT, null);
        }

        public static Claim replay(String snapshot) {
            return new Claim(Outcome.REPLAY, snapshot);
        }

        public boolean isClaimed() {
            return outcome == Outcome.CLAIMED;
        }
    }

    /**
     * Atomically attempt to claim {@code key} before anything is created.
     *
     * @param key the client-supplied idempotency key (un-namespaced; the implementation owns any
     *            storage prefix)
     * @return {@link Outcome#CLAIMED} if this caller won the key and must proceed;
     *         {@link Outcome#REPLAY} with the stored snapshot if a previous request already answered;
     *         {@link Outcome#IN_FLIGHT} if a concurrent duplicate holds the claim
     */
    Claim claim(String key);

    /**
     * Store the response for a key this caller {@link Outcome#CLAIMED}, making it replayable for the
     * remainder of the TTL. Idempotent and never throws for a claim that has already lapsed — the
     * transaction was still created, so the caller must still be able to answer.
     */
    void complete(String key, String responseSnapshot);

    /**
     * Give up a claim without storing a response, so an immediate retry may create. Called when the
     * create failed: holding the claim would make a failed attempt block the real one.
     */
    void release(String key);

    /**
     * @return the stored response snapshot for {@code key}, or empty if the key is unknown, still only
     *         claimed (never completed), or its TTL has elapsed
     */
    Optional<String> get(String key);
}
