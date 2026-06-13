package com.gme.pay.prefunding.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link BalanceAlertEntity} ({@code balance_alert}, Slice 5). */
@Repository
public interface BalanceAlertRepository extends JpaRepository<BalanceAlertEntity, Long> {

    /**
     * Latest alert for one (partner, tier) pair — the hysteresis anchor: an
     * unacknowledged latest alert suppresses re-raising that tier while the
     * balance oscillates around the boundary.
     */
    Optional<BalanceAlertEntity> findTopByPartnerCodeAndTierOrderByIdDesc(
            String partnerCode, String tier);

    /** All alerts for a partner, newest first (Admin UI alert feed). */
    List<BalanceAlertEntity> findByPartnerCodeOrderByIdDesc(String partnerCode);

    long countByPartnerCodeAndTier(String partnerCode, String tier);
}
