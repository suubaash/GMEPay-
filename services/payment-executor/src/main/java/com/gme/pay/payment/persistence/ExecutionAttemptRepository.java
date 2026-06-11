package com.gme.pay.payment.persistence;

import com.gme.pay.payment.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Spring Data repository over {@code execution_attempts} (Flyway V001, ticket 17.2-G08). */
public interface ExecutionAttemptRepository extends JpaRepository<ExecutionAttemptEntity, Long> {

    /** All attempts recorded against a transaction, oldest first. */
    List<ExecutionAttemptEntity> findByTxnRefOrderByCreatedAtAscIdAsc(String txnRef);

    /** Latest attempt for a partner's own transaction reference (retry/duplicate lookups). */
    Optional<ExecutionAttemptEntity> findFirstByPartnerIdAndPartnerTxnRefOrderByCreatedAtDescIdDesc(
            long partnerId, String partnerTxnRef);

    long countByOutcome(PaymentStatus outcome);
}
