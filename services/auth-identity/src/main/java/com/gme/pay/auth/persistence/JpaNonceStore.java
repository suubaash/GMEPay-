package com.gme.pay.auth.persistence;

import com.gme.pay.auth.domain.NonceStore;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * JPA-backed {@link NonceStore} implementation that persists observed nonces
 * to the {@code used_nonces} table (see V001__create_nonces.sql).
 *
 * <p>Marked {@code @Primary} so it wins over the fallback
 * {@link com.gme.pay.auth.domain.InMemoryNonceStore} bean (kept for tests
 * and local in-process development).</p>
 *
 * <p>Atomicity: the primary-key uniqueness on {@code nonce} provides the
 * atomic check-and-set guarantee. If two requests race, only one INSERT
 * succeeds; the other raises {@link DataIntegrityViolationException},
 * which is interpreted as a replay.</p>
 *
 * <p>Note: the {@code ttl} parameter is recorded as {@code used_at} only;
 * pruning of expired rows is handled by an out-of-band sweep job (not in
 * Phase 1 scope). The row's presence is what blocks replays during the
 * partner-relevant window.</p>
 */
@Component
@Primary
public class JpaNonceStore implements NonceStore {

    private final NonceRepository repository;

    public JpaNonceStore(NonceRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public boolean checkAndSet(String partnerId, String nonce, Duration ttl) {
        // Fast path: row already exists => replay.
        if (repository.existsById(nonce)) {
            return false;
        }
        try {
            repository.save(new NonceEntity(nonce, partnerId, Instant.now()));
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            // Lost the race against a concurrent insert — treat as replay.
            return false;
        }
    }
}
