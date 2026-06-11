package com.gme.pay.registry.audit;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link AuditLogEntity}. Read-only API:
 * {@link AuditLogService} owns all writes and never mutates an existing row.
 *
 * <p>The two query methods cover the two callers in Slice 1:
 *
 * <ul>
 *   <li>{@link #findLatestByAggregate} — fetched by the writer to obtain the
 *       prior row's {@code row_hash}, which becomes the new row's {@code prev_hash}.
 *       Returns at most one row.</li>
 *   <li>{@link #findChainByAggregate} — fetched by the verifier (in tests and
 *       eventually by a chain-validate endpoint) to walk every row of a given
 *       aggregate in {@code id} order.</li>
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
}
