package com.gme.pay.prefunding.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link PartnerBalanceEntity}. Atomic balance mutations MUST acquire a row-level
 * write lock via {@link #lockByPartnerId(String)}, which translates to a PostgreSQL
 * {@code SELECT ... FOR UPDATE} (and the equivalent in H2 PostgreSQL mode).
 */
@Repository
public interface PartnerBalanceRepository extends JpaRepository<PartnerBalanceEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM PartnerBalanceEntity b WHERE b.partnerId = :partnerId")
    Optional<PartnerBalanceEntity> lockByPartnerId(@Param("partnerId") String partnerId);
}
