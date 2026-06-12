package com.gme.pay.registry.scheme;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link PartnerSchemeEntity}
 * ({@code partner_scheme}, V022).
 *
 * <p>Under SCD-6 (ADR-010) every step-7 save supersedes the prior set and
 * inserts a fresh one, so "the partner's schemes" always means the CURRENT
 * rows: {@code superseded_at IS NULL}. Historical rows remain in the table for
 * as-of inspection; no API deletes anything.
 */
@Repository
public interface PartnerSchemeRepository extends JpaRepository<PartnerSchemeEntity, Long> {

    /**
     * The CURRENT scheme set for the given partner surrogate id, in id order
     * (deterministic for the canonical audit snapshot). Served by
     * {@code idx_partner_scheme_current}.
     */
    @Query("""
            select s from PartnerSchemeEntity s
            where s.partnerId = :partnerId
              and s.supersededAt is null
            order by s.id
            """)
    List<PartnerSchemeEntity> findAllCurrentByPartnerId(@Param("partnerId") Long partnerId);
}
