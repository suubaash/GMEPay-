package com.gme.pay.registry.ops;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link OpsSuspensionEntity}. The operational-status read pulls
 * only ACTIVE rows; the suspend/unsuspend commands look up the current row for a
 * given (type, id) to stay idempotent.
 */
@Repository
public interface OpsSuspensionRepository extends JpaRepository<OpsSuspensionEntity, Long> {

    /** All currently-active suspensions, oldest first, for the status aggregation. */
    @Query("""
            select s from OpsSuspensionEntity s
            where s.active = true
            order by s.entityType asc, s.entityId asc
            """)
    List<OpsSuspensionEntity> findAllActive();

    /**
     * The single suspension row for a (type, id) pair regardless of active flag.
     * Used by suspend (to reactivate/update in place) and unsuspend (to clear).
     * Returns the most recent by id when — defensively — more than one exists.
     */
    @Query("""
            select s from OpsSuspensionEntity s
            where s.entityType = :entityType
              and s.entityId = :entityId
            order by s.id desc
            """)
    List<OpsSuspensionEntity> findByEntityRaw(
            @Param("entityType") String entityType,
            @Param("entityId") String entityId);

    default Optional<OpsSuspensionEntity> findByEntity(String entityType, String entityId) {
        List<OpsSuspensionEntity> rows = findByEntityRaw(entityType, entityId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
