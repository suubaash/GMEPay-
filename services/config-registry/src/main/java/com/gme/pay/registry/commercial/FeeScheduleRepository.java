package com.gme.pay.registry.commercial;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link FeeScheduleEntity}
 * ({@code partner_fee_schedule}, V018).
 *
 * <p>Under SCD-6 (ADR-010) every step-6 save supersedes the prior set and
 * inserts a fresh one, so "the partner's fee schedule" always means the
 * CURRENT rows: {@code superseded_at IS NULL}. Historical rows remain for
 * as-of inspection; no API deletes anything.
 */
@Repository
public interface FeeScheduleRepository extends JpaRepository<FeeScheduleEntity, Long> {

    /**
     * The CURRENT fee-schedule rows for the given partner surrogate id, in id
     * (insertion) order. Served by {@code idx_partner_fee_schedule_current}.
     */
    @Query("""
            select f from FeeScheduleEntity f
            where f.partnerId = :partnerId
              and f.supersededAt is null
            order by f.id
            """)
    List<FeeScheduleEntity> findCurrentByPartnerId(@Param("partnerId") Long partnerId);
}
