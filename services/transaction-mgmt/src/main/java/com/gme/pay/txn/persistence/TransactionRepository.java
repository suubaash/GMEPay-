package com.gme.pay.txn.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * Spring Data JPA repository for {@link TransactionEntity} rows.
 *
 * <p>Lives in the {@code persistence} package alongside the entity – kept
 * separate from {@code com.gme.pay.txn.service.TransactionRepository}, which is
 * the domain-layer port over the aggregate.  The in-memory adapter
 * {@code InMemoryTransactionRepository} now delegates to this interface so the
 * service layer is unaffected.
 *
 * <p>V003 adds a paged query for GET /v1/transactions.
 */
@Repository
public interface TransactionRepository extends JpaRepository<TransactionEntity, String> {

    /**
     * Paged query for GET /v1/transactions with optional filters.
     * All filter parameters are nullable — pass {@code null} to skip that filter.
     *
     * @param fromInstant   lower bound (inclusive) on created_at (null = no lower bound)
     * @param toInstant     upper bound (exclusive) on created_at (null = no upper bound)
     * @param status        filter by status name (null = all statuses)
     * @param partnerId     filter by partner_id (null = all partners)
     * @param pageable      pagination/sort spec
     */
    @Query("""
            SELECT t FROM TransactionEntity t
            WHERE (:fromInstant IS NULL OR t.createdAt >= :fromInstant)
              AND (:toInstant   IS NULL OR t.createdAt <  :toInstant)
              AND (:status      IS NULL OR t.status    =  :status)
              AND (:partnerId   IS NULL OR t.partnerId =  :partnerId)
            """)
    Page<TransactionEntity> findByFilters(
            @Param("fromInstant") Instant fromInstant,
            @Param("toInstant")   Instant toInstant,
            @Param("status")      String status,
            @Param("partnerId")   Long partnerId,
            Pageable pageable);
}
