package com.gme.pay.registry.commercial;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link PartnerCommissionShareEntity}
 * ({@code partner_commission_share}, V031).
 *
 * <p>Under SCD-6 (ADR-010) "the partner's commission share" always means the
 * CURRENT rows: {@code superseded_at IS NULL}. Historical rows remain for
 * as-of inspection; no API deletes anything.
 */
@Repository
public interface PartnerCommissionShareRepository
        extends JpaRepository<PartnerCommissionShareEntity, Long> {

    /**
     * The CURRENT commission-share rows for the given partner surrogate id, in
     * id (insertion) order. Served by {@code idx_partner_commission_share_current}.
     */
    @Query("""
            select c from PartnerCommissionShareEntity c
            where c.partnerId = :partnerId
              and c.supersededAt is null
            order by c.id
            """)
    List<PartnerCommissionShareEntity> findCurrentByPartnerId(@Param("partnerId") Long partnerId);
}
