package com.gme.pay.registry.scheme;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link SchemeCommissionShareEntity}
 * ({@code scheme_commission_share}, V031).
 *
 * <p>Under SCD-6 (ADR-010) every save supersedes the prior set and inserts a
 * fresh one, so "the scheme's commission share" always means the CURRENT rows:
 * {@code superseded_at IS NULL}. Historical rows remain for as-of inspection;
 * no API deletes anything.
 */
@Repository
public interface SchemeCommissionShareRepository
        extends JpaRepository<SchemeCommissionShareEntity, Long> {

    /**
     * The CURRENT commission-share rows for the given scheme code, in id
     * (insertion) order. Served by {@code idx_scheme_commission_share_current}.
     */
    @Query("""
            select s from SchemeCommissionShareEntity s
            where s.schemeId = :schemeId
              and s.supersededAt is null
            order by s.id
            """)
    List<SchemeCommissionShareEntity> findCurrentBySchemeId(@Param("schemeId") String schemeId);
}
