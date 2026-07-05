package com.gme.pay.scheme.zeropay.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

/** Running-balance store for GME's ZeroPay prepaid float ({@code gme_scheme_balance}). */
public interface GmeSchemeBalanceRepository extends JpaRepository<GmeSchemeBalanceEntity, String> {

    /**
     * Load the balance row for update under a pessimistic write lock, so concurrent debits/credits
     * serialise on the single row rather than racing a read-modify-write.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<GmeSchemeBalanceEntity> findWithLockBySchemeCode(String schemeCode);
}
