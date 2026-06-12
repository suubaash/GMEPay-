package com.gme.pay.registry.commercial;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link FxConfigEntity}
 * ({@code partner_fx_config}, V019). Same current-row contract as
 * {@code PrefundingConfigRepository}: {@code superseded_at IS NULL} is the
 * partner's live FX config; history stays for as-of inspection.
 */
@Repository
public interface FxConfigRepository extends JpaRepository<FxConfigEntity, Long> {

    /**
     * The CURRENT FX config for the given partner surrogate id.
     * Served by {@code idx_partner_fx_config_current}.
     */
    @Query("""
            select f from FxConfigEntity f
            where f.partnerId = :partnerId
              and f.supersededAt is null
            """)
    Optional<FxConfigEntity> findCurrentByPartnerId(@Param("partnerId") Long partnerId);
}
