package com.gme.pay.registry.corridor;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link PartnerCorridorEntity}
 * ({@code partner_corridor}, V023).
 *
 * <p>Under SCD-6 (ADR-010) every step-7 save supersedes the prior set and
 * inserts a fresh one, so "the partner's corridors" always means the CURRENT
 * rows: {@code superseded_at IS NULL}. Historical rows remain in the table for
 * as-of inspection; no API deletes anything.
 */
@Repository
public interface PartnerCorridorRepository extends JpaRepository<PartnerCorridorEntity, Long> {

    /**
     * The CURRENT corridor set for the given partner surrogate id, in id order
     * (deterministic for the canonical audit snapshot). Served by
     * {@code idx_partner_corridor_current}.
     */
    @Query("""
            select c from PartnerCorridorEntity c
            where c.partnerId = :partnerId
              and c.supersededAt is null
            order by c.id
            """)
    List<PartnerCorridorEntity> findAllCurrentByPartnerId(@Param("partnerId") Long partnerId);

    /**
     * The CURRENT row for one exact corridor lane of the partner, if any —
     * the lookup the gateway's corridor gate and the SchemeRouter resolve a
     * transaction against. At most one row can match (the V023 partial-unique
     * index on the corridor key over current rows).
     */
    @Query("""
            select c from PartnerCorridorEntity c
            where c.partnerId = :partnerId
              and c.srcCountry = :srcCountry
              and c.srcCcy = :srcCcy
              and c.dstCountry = :dstCountry
              and c.dstCcy = :dstCcy
              and c.supersededAt is null
            """)
    Optional<PartnerCorridorEntity> findCurrentByPartnerIdAndCorridor(
            @Param("partnerId") Long partnerId,
            @Param("srcCountry") String srcCountry,
            @Param("srcCcy") String srcCcy,
            @Param("dstCountry") String dstCountry,
            @Param("dstCcy") String dstCcy);
}
