package com.gme.pay.registry.settlement;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link SettlementConfigEntity}
 * ({@code partner_settlement_config}, V013).
 *
 * <p>Under SCD-6 (ADR-010) every step-4 settlement save supersedes the prior
 * row and inserts a fresh one, so "the partner's settlement config" always
 * means the CURRENT row: {@code superseded_at IS NULL}. Historical rows remain
 * for as-of inspection; no API deletes anything.
 */
@Repository
public interface SettlementConfigRepository extends JpaRepository<SettlementConfigEntity, Long> {

    /**
     * The CURRENT settlement config row for the given partner surrogate id (at
     * most one — the service serialises writes per partner). Served by
     * {@code idx_partner_settlement_config_current}.
     */
    @Query("""
            select s from SettlementConfigEntity s
            where s.partnerId = :partnerId
              and s.supersededAt is null
            """)
    Optional<SettlementConfigEntity> findCurrentByPartnerId(@Param("partnerId") Long partnerId);
}
