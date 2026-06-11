package com.gme.pay.ratefx.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/** Spring Data repository for the durable rate-quote audit table. */
public interface RateQuoteRepository extends JpaRepository<RateQuoteEntity, String> {

    /** Audit/housekeeping: quotes whose TTL deadline passed before {@code cutoff}. */
    List<RateQuoteEntity> findByExpiresAtBefore(Instant cutoff);
}
