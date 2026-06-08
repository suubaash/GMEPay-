package com.gme.pay.auth.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link NonceStore} implementation.
 *
 * Intended for local development and unit tests only.
 * Production deployments must replace this with a Redis-backed store
 * (shared across gateway instances) to guarantee replay protection.
 *
 * Expired entries are lazily evicted on each {@link #checkAndSet} call.
 */
public class InMemoryNonceStore implements NonceStore {

    private final ConcurrentHashMap<String, Instant> store = new ConcurrentHashMap<>();

    @Override
    public boolean checkAndSet(String partnerId, String nonce, Duration ttl) {
        String key = partnerId + ":" + nonce;
        Instant expiry = Instant.now().plus(ttl);

        // Evict expired entries lazily (keep memory bounded in low-volume dev usage)
        store.entrySet().removeIf(e -> e.getValue().isBefore(Instant.now()));

        // Atomic: only set if key is absent
        Instant previous = store.putIfAbsent(key, expiry);
        return previous == null; // true = first time seen; false = replay
    }
}
