package com.gme.pay.registry.persistence;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link PartnerEntity}. The primary key is the
 * business {@code partner_id} (VARCHAR(32)) — there is no surrogate.
 */
@Repository
public interface PartnerRepository extends JpaRepository<PartnerEntity, String> {

    /**
     * Point-in-time lookup against the half-open validity window
     * {@code [effective_from, effective_to)}:
     * <ul>
     *   <li>{@code at == effective_from} matches (lower bound inclusive)</li>
     *   <li>{@code at == effective_to} does NOT match (upper bound exclusive)</li>
     *   <li>{@code effective_to IS NULL} means open-ended</li>
     * </ul>
     */
    @Query("""
            select p from PartnerEntity p
            where p.partnerId = :partnerId
              and p.effectiveFrom <= :at
              and (p.effectiveTo is null or p.effectiveTo > :at)
            """)
    Optional<PartnerEntity> findEffectiveAt(@Param("partnerId") String partnerId, @Param("at") Instant at);
}
