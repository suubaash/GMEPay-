package com.gme.pay.ledger.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the rounding-residual idempotency guard ({@code rounding_residual_keys}).
 * Keyed by {@code reference}; presence of a row means a residual was already posted for that reference.
 */
public interface RoundingResidualKeyRepository extends JpaRepository<RoundingResidualKeyEntity, String> {
}
