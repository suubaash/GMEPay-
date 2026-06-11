package com.gme.pay.ratefx.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

/** Spring Data repository for treasury rate snapshots. */
public interface RateSnapshotRepository extends JpaRepository<RateSnapshotEntity, String> {

    /**
     * LIVE-source resolution (RATE-04 §3.2): the most recent snapshot for a currency
     * with the given source whose effective_at is at or before {@code asOf}.
     */
    Optional<RateSnapshotEntity> findFirstByCurrencyCodeAndSourceAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(
            String currencyCode, String source, Instant asOf);
}
