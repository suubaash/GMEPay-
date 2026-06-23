package com.gme.pay.registry.scheme;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link MerchantFeeScheduleEntity}
 * ({@code merchant_fee_schedule}, V032). CURRENT rows = {@code superseded_at IS NULL}.
 */
@Repository
public interface MerchantFeeScheduleRepository
        extends JpaRepository<MerchantFeeScheduleEntity, Long> {

    /**
     * The CURRENT merchant-fee rows for the given scheme code, in id (insertion)
     * order. Served by {@code idx_merchant_fee_schedule_current}.
     */
    @Query("""
            select m from MerchantFeeScheduleEntity m
            where m.schemeId = :schemeId
              and m.supersededAt is null
            order by m.id
            """)
    List<MerchantFeeScheduleEntity> findCurrentBySchemeId(@Param("schemeId") String schemeId);
}
