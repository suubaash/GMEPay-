package com.gme.pay.registry.regulatory;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link PartnerRegulatoryConfigEntity}
 * ({@code partner_regulatory_config}, V029).
 *
 * <p>Under SCD-6 (ADR-010) every step-8 save supersedes the prior row and
 * inserts a fresh one, so "the partner's regulatory config" always means the
 * CURRENT row: {@code superseded_at IS NULL}. Historical rows remain for
 * as-of inspection; no API deletes anything.
 *
 * <h2>Activation-gate integration (Slice 8 Lane A seam)</h2>
 *
 * <p>{@link #existsCurrentByPartnerId} is the query Lane A's
 * {@code ActivationGateService} calls for the hard LIVE pre-condition
 * "a current {@code partner_regulatory_config} row exists for the partner".
 * It is deliberately a boolean projection (no entity materialisation) so the
 * gate evaluation stays a single index-only probe of
 * {@code idx_partner_regulatory_current}.
 */
@Repository
public interface PartnerRegulatoryConfigRepository
        extends JpaRepository<PartnerRegulatoryConfigEntity, Long> {

    /**
     * The CURRENT regulatory config for the given partner surrogate id, if
     * any. Served by {@code idx_partner_regulatory_current}; at most one row
     * can match (the {@code current_partner_key} UNIQUE emulation).
     */
    @Query("""
            select c from PartnerRegulatoryConfigEntity c
            where c.partnerId = :partnerId
              and c.supersededAt is null
            """)
    Optional<PartnerRegulatoryConfigEntity> findCurrentByPartnerId(
            @Param("partnerId") Long partnerId);

    /**
     * TRUE when a CURRENT regulatory config row exists for the partner — the
     * Slice 8 activation-gate pre-condition for LIVE (see class note).
     */
    @Query("""
            select count(c) > 0 from PartnerRegulatoryConfigEntity c
            where c.partnerId = :partnerId
              and c.supersededAt is null
            """)
    boolean existsCurrentByPartnerId(@Param("partnerId") Long partnerId);
}
