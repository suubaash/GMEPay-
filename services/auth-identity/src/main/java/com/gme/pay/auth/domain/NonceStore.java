package com.gme.pay.auth.domain;

import java.time.Duration;

/**
 * Port for nonce replay-protection (SEC-09 §3.3, API-05 §3.6).
 *
 * Implementations atomically check-and-set a (partnerId, nonce) pair with the given TTL.
 * If the entry did not previously exist, it is created and {@code true} is returned.
 * If the entry already exists (replay detected), {@code false} is returned.
 *
 * Production implementation uses Redis SET NX EX (see RedisNonceStore in api-gateway).
 * Tests may substitute an in-memory ConcurrentHashMap implementation.
 */
public interface NonceStore {

    /**
     * Atomically marks a nonce as seen.
     *
     * @param partnerId partner identifier string
     * @param nonce     unique nonce from X-Nonce header
     * @param ttl       how long to retain the nonce entry
     * @return true if this is the first time the nonce was seen (OK to proceed);
     *         false if a replay is detected (reject with 401 REPLAY_DETECTED)
     */
    boolean checkAndSet(String partnerId, String nonce, Duration ttl);
}
