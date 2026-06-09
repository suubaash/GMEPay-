package com.gme.pay.ledger.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link LedgerEntryEntity}.
 *
 * <p>Lines are looked up by {@code journalId} (to reconstruct a journal) or by
 * {@code reference} (to find all entries posted against one transaction reference,
 * which is the primary report query).
 */
public interface LedgerEntryEntityRepository extends JpaRepository<LedgerEntryEntity, Long> {

    /** All entries for one journal, in insertion order. */
    List<LedgerEntryEntity> findByJournalIdOrderByIdAsc(String journalId);

    /** All entries posted for a transaction reference across any journal. */
    List<LedgerEntryEntity> findByReferenceOrderByIdAsc(String reference);

    /** All entries posted to a specific account (used by aggregation queries). */
    List<LedgerEntryEntity> findByAccountOrderByIdAsc(String account);
}
