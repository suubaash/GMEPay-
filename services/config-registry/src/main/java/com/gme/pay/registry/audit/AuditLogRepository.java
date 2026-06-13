package com.gme.pay.registry.audit;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link AuditLogEntity}. Read-only API:
 * {@link AuditLogService} owns all writes and never mutates an existing row.
 *
 * <p>Query methods:
 *
 * <ul>
 *   <li>{@link #findLatestByAggregate} — fetched by the writer to obtain the
 *       prior row's {@code row_hash}, which becomes the new row's {@code prev_hash}.
 *       Returns at most one row.</li>
 *   <li>{@link #findChainByAggregate} — fetched by the verifier (in tests and
 *       by the chain-validate endpoint) to walk every row of a given aggregate
 *       in {@code id} order.</li>
 *   <li>{@link #findPageByAggregate} — paginated read for the audit read
 *       endpoint ({@code GET /v1/audit}), ordered {@code recorded_at DESC}.</li>
 * </ul>
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    /**
     * The single most recent row for the given aggregate, by {@code id} order.
     * {@code id} is a strictly-monotonic BIGSERIAL so "latest by id" is exactly
     * "latest written" — using {@code recorded_at} instead would lose the order
     * within the same millisecond (two rows of the same partner written in the
     * same write would race).
     */
    @Query("""
            select a from AuditLogEntity a
            where a.aggregateType = :aggregateType
              and a.aggregateId = :aggregateId
            order by a.id desc
            """)
    List<AuditLogEntity> findLatestByAggregateRaw(
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") String aggregateId,
            org.springframework.data.domain.Pageable pageable);

    default Optional<AuditLogEntity> findLatestByAggregate(String aggregateType, String aggregateId) {
        List<AuditLogEntity> rows = findLatestByAggregateRaw(
                aggregateType, aggregateId, PageRequest.of(0, 1));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Every row for the given aggregate, ordered by {@code id} ascending — which
     * is the order the chain was written and therefore the order verification
     * must walk it.
     */
    @Query("""
            select a from AuditLogEntity a
            where a.aggregateType = :aggregateType
              and a.aggregateId = :aggregateId
            order by a.id asc
            """)
    List<AuditLogEntity> findChainByAggregate(
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") String aggregateId);

    /**
     * Paginated read for the audit read endpoint, ordered {@code recorded_at DESC}
     * (newest first). The count query is a separate {@code countQuery} to avoid a
     * subselect scan on the JSONB/BLOB columns which are not needed for counting.
     *
     * <p>Used by {@code AuditLogController.list}; the chain-validity check is done
     * separately by {@code AuditLogService.verifyChain} which reads in {@code id
     * ASC} order — the two queries are intentionally separate so pagination ordering
     * and chain verification ordering do not interfere.
     */
    @Query(value = """
            select a from AuditLogEntity a
            where a.aggregateType = :aggregateType
              and a.aggregateId = :aggregateId
            order by a.recordedAt desc, a.id desc
            """,
           countQuery = """
            select count(a) from AuditLogEntity a
            where a.aggregateType = :aggregateType
              and a.aggregateId = :aggregateId
            """)
    Page<AuditLogEntity> findPageByAggregate(
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") String aggregateId,
            Pageable pageable);
}
