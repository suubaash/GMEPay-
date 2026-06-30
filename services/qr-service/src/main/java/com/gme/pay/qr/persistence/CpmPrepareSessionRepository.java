package com.gme.pay.qr.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Spring Data repository for {@link CpmPrepareSessionEntity} (WBS 5.3-T01). */
public interface CpmPrepareSessionRepository extends JpaRepository<CpmPrepareSessionEntity, String> {

    Optional<CpmPrepareSessionEntity> findByPaymentId(String paymentId);

    boolean existsByPartnerTxnRef(String partnerTxnRef);

    List<CpmPrepareSessionEntity> findByStatusAndExpiresAtBefore(String status, Instant cutoff);
}
