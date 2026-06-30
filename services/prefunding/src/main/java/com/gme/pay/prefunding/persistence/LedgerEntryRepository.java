package com.gme.pay.prefunding.persistence;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for the append-only {@link LedgerEntryEntity} ledger. */
@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, Long> {

    List<LedgerEntryEntity> findByPartnerIdOrderByCreatedAtAscIdAsc(String partnerId);

    long countByPartnerId(String partnerId);

    /** All ledger entries for one (partner, txnRef) — used to compute + guard a reversal. */
    List<LedgerEntryEntity> findByPartnerIdAndTxnRef(String partnerId, String txnRef);

    /**
     * Most-recent-first deduction history for a partner, bounded by {@code Pageable} (limit N).
     * Backs {@code GET /v1/prefunding/{code}/deductions}; ordered by created-at then id descending so
     * ties within the same instant are deterministic.
     */
    List<LedgerEntryEntity> findByPartnerIdAndEntryTypeOrderByCreatedAtDescIdDesc(
            String partnerId, String entryType, Pageable pageable);
}
