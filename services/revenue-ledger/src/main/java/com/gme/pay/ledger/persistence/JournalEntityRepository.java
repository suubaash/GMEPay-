package com.gme.pay.ledger.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
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

    /**
     * Page of journal heads posted in the {@code [from, to)} window (end-exclusive instant),
     * ordered as requested by the {@link Pageable} sort (the read API sorts {@code postedAt} DESC,
     * newest-first). Used by {@code GET /v1/journals} — only the page's heads are materialised;
     * their lines are batch-loaded separately by
     * {@link LedgerEntryEntityRepository#findByJournalIdInOrderByJournalIdAscIdAsc(java.util.Collection)}.
     */
    Page<JournalEntity> findByPostedAtGreaterThanEqualAndPostedAtLessThan(
            Instant from, Instant to, Pageable pageable);

    /** As above, additionally filtered to a single transaction {@code reference}. */
    Page<JournalEntity> findByReferenceAndPostedAtGreaterThanEqualAndPostedAtLessThan(
            String reference, Instant from, Instant to, Pageable pageable);
}
