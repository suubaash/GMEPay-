package com.gme.pay.ledger.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link JournalEntity}.
 *
 * <p>The primary key is the externally-supplied {@code journal_id} (UUID string),
 * matching how {@link com.gme.pay.ledger.domain.model.Journal} mints its own id at creation.
 */
public interface JournalEntityRepository extends JpaRepository<JournalEntity, String> {

    /** Find every journal posted for a given transaction reference. */
    List<JournalEntity> findByReferenceOrderByPostedAtAsc(String reference);
}
