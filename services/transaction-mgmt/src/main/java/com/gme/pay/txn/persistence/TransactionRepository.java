package com.gme.pay.txn.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link TransactionEntity} rows.
 *
 * <p>Lives in the {@code persistence} package alongside the entity – kept
 * separate from {@code com.gme.pay.txn.service.TransactionRepository}, which is
 * the domain-layer port over the aggregate.  The in-memory adapter
 * {@code InMemoryTransactionRepository} now delegates to this interface so the
 * service layer is unaffected.
 */
@Repository
public interface TransactionRepository extends JpaRepository<TransactionEntity, String> {
}
