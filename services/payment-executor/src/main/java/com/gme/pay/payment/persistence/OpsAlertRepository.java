package com.gme.pay.payment.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository over {@code ops_alerts} (Flyway V006, gap T3-3).
 *
 * <p>Every read is <b>bounded</b> by a {@link Pageable} supplied by the caller — the ops query surface
 * must never be able to pull an unbounded history into memory, which is the failure mode the old
 * in-memory deque "solved" by silently discarding everything past 200 entries.
 *
 * <p>The optional filters are expressed as {@code :param IS NULL OR ...} so one query serves the
 * filtered and unfiltered cases without string-built JPQL.
 */
public interface OpsAlertRepository extends JpaRepository<OpsAlertEntity, Long> {

    /**
     * Newest-first alerts, optionally narrowed by severity and/or alert type (exact,
     * case-insensitive; {@code null} = no constraint).
     */
    @Query("""
            SELECT a FROM OpsAlertEntity a
            WHERE (:severity IS NULL OR UPPER(a.severity) = UPPER(:severity))
              AND (:alertType IS NULL OR UPPER(a.alertType) = UPPER(:alertType))
            ORDER BY a.occurredAt DESC, a.id DESC
            """)
    List<OpsAlertEntity> findRecent(@Param("severity") String severity,
                                    @Param("alertType") String alertType,
                                    Pageable pageable);

    /** Retention pruner: drop rows older than the cutoff. Returns the number deleted. */
    @Modifying
    @Query("DELETE FROM OpsAlertEntity a WHERE a.occurredAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
