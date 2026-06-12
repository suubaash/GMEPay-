package com.gme.pay.registry.prefunding;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link PrefundingConfigEntity}
 * ({@code partner_prefunding_config}, V015).
 *
 * <p>Under SCD-6 (ADR-010) every step-5 save supersedes the prior row and
 * inserts a fresh one, so "the partner's prefunding config" always means the
 * CURRENT row: {@code superseded_at IS NULL}. Historical rows remain in the
 * table for as-of inspection; no API deletes anything.
 */
@Repository
public interface PrefundingConfigRepository extends JpaRepository<PrefundingConfigEntity, Long> {

    /**
     * The CURRENT prefunding config for the given partner surrogate id.
     * Served by {@code idx_partner_prefunding_config_current}.
     */
    @Query("""
            select p from PrefundingConfigEntity p
            where p.partnerId = :partnerId
              and p.supersededAt is null
            """)
    Optional<PrefundingConfigEntity> findCurrentByPartnerId(@Param("partnerId") Long partnerId);
}
