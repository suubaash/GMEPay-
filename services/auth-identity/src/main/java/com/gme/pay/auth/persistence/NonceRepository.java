package com.gme.pay.auth.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository over {@link NonceEntity}.
 *
 * The nonce string is the primary key; {@link JpaRepository#existsById(Object)}
 * is the "seen" check used by the JpaNonceStore. Inserts via {@code save(...)}
 * fail with a unique-constraint violation if the row already exists — that
 * race is treated as a replay detection by the calling store.
 */
public interface NonceRepository extends JpaRepository<NonceEntity, String> {
}
