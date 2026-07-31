package com.gme.pay.bff.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository over {@code ops_alerts} (Flyway V001).
 *
 * <p>Every read is <b>bounded</b> by a {@link Pageable} the caller supplies. The store this replaces
 * bounded reads by silently discarding everything past 200 entries; a durable table must not replace
 * that with an unbounded fetch into the heap of an operator surface.
 *
 * <p>Optional filters are expressed as {@code :param IS NULL OR ...} so one query serves the filtered
 * and unfiltered cases without string-built JPQL.
 */
public interface OpsAlertRepository extends JpaRepository<OpsAlertEntity, Long> {

    /**
     * Newest-first alerts, optionally narrowed by severity and/or alert type (exact,
     * case-insensitive; {@code null} = no constraint).
     *
     * <p>Ordered by {@code seq DESC}, which is insertion order — byte-for-byte the ordering the
     * in-memory deque gave. Deliberately NOT {@code occurredAt DESC}: {@code occurredAt} is the
     * producer's own string and is not guaranteed to be a parseable instant, so ordering on it would
     * be ordering on text.
     */
    @Query("""
            SELECT a FROM OpsAlertEntity a
            WHERE (:severity IS NULL OR UPPER(a.severity) = UPPER(:severity))
              AND (:alertType IS NULL OR UPPER(a.alertType) = UPPER(:alertType))
            ORDER BY a.seq DESC
            """)
    List<OpsAlertEntity> findRecent(@Param("severity") String severity,
                                    @Param("alertType") String alertType,
                                    Pageable pageable);

    /**
     * Load one alert for update, holding the row exclusively until the surrounding transaction
     * commits ({@code SELECT ... FOR UPDATE}).
     *
     * <p><b>This is the reason the store is a table and not a Redis hash.</b> The paging stamp (Kafka
     * listener thread and the escalation sweep) and the operator ack (a request thread, possibly on
     * another replica) are two independently-written parts of the same row. Without the row lock the
     * later writer's read-modify-write silently drops the earlier one's field, and the observable
     * symptom is an alert that displays as never-paged after an operator acks it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM OpsAlertEntity a WHERE a.seq = :seq")
    Optional<OpsAlertEntity> findByIdForUpdate(@Param("seq") long seq);

    /** Retention pruner: drop rows written before the cutoff. Returns the number deleted. */
    @Modifying
    @Query("DELETE FROM OpsAlertEntity a WHERE a.createdAt < :cutoff")
    int deleteWrittenBefore(@Param("cutoff") Instant cutoff);
}
