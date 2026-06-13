package com.gme.pay.registry.commercial;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link LimitsEntity}
 * ({@code partner_limits}, V020). Same current-row contract as
 * {@code PrefundingConfigRepository}: {@code superseded_at IS NULL} is the
 * partner's live limit set; history stays for as-of inspection.
 */
@Repository
public interface LimitsRepository extends JpaRepository<LimitsEntity, Long> {

    /**
     * The CURRENT limits row for the given partner surrogate id.
     * Served by {@code idx_partner_limits_current}.
     */
    @Query("""
            select l from LimitsEntity l
            where l.partnerId = :partnerId
              and l.supersededAt is null
            """)
    Optional<LimitsEntity> findCurrentByPartnerId(@Param("partnerId") Long partnerId);
}
