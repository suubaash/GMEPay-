package com.gme.pay.bff.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * Spring Data repository over {@code operator_action_audit} (Flyway V002).
 *
 * <p>Write-mostly: the value of the table is that the rows exist and survive. The one read is bounded
 * and exists so a test — and a human with a JDBC session — can assert what was recorded.
 */
public interface OperatorActionAuditRepository extends JpaRepository<OperatorActionAuditEntity, Long> {

    /** Newest-first operator actions, bounded by the caller's {@link Pageable}. */
    @Query("SELECT a FROM OperatorActionAuditEntity a ORDER BY a.id DESC")
    List<OperatorActionAuditEntity> findRecent(Pageable pageable);
}
