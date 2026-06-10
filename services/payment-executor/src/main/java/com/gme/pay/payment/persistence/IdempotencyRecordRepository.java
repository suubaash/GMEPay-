package com.gme.pay.payment.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

/** Spring Data repository over {@code idempotency_keys} (Flyway V002, ticket 17.2-G08). */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecordEntity, Long> {

    Optional<IdempotencyRecordEntity> findByPartnerIdAndIdempotencyKey(long partnerId, String idempotencyKey);

    boolean existsByPartnerIdAndIdempotencyKey(long partnerId, String idempotencyKey);

    /** Housekeeping: removes keys whose retention window has lapsed. Returns rows deleted. */
    long deleteByExpiresAtBefore(Instant cutoff);
}
