package com.gme.pay.kybadapter.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository over the {@code kyb_screening} run log. The unique
 * {@code provider_ref} index backs both lookups: idempotent re-screen
 * ({@link #findByProviderRef}) and the {@code GET /v1/kyb/result/{ref}}
 * retrieval endpoint.
 */
public interface KybScreeningRepository extends JpaRepository<KybScreeningRecord, Long> {

    /** The persisted run for a deterministic provider reference, if any. */
    Optional<KybScreeningRecord> findByProviderRef(String providerRef);
}
