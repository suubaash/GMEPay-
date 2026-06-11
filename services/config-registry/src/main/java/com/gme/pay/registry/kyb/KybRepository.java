package com.gme.pay.registry.kyb;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link KybEntity} ({@code partner_kyb}, V011).
 *
 * <p>Under SCD-6 (ADR-010) every step-3 save / screening run supersedes the
 * prior row and inserts a fresh one, so "the partner's KYB" always means the
 * CURRENT row: {@code superseded_at IS NULL}. Historical rows remain for
 * as-of inspection; no API deletes anything.
 */
@Repository
public interface KybRepository extends JpaRepository<KybEntity, Long> {

    /**
     * The CURRENT KYB row for the given partner surrogate id (at most one —
     * the service serialises writes per partner). Served by
     * {@code idx_partner_kyb_current}.
     */
    @Query("""
            select k from KybEntity k
            where k.partnerId = :partnerId
              and k.supersededAt is null
            """)
    Optional<KybEntity> findCurrentByPartnerId(@Param("partnerId") Long partnerId);
}
