package com.gme.pay.prefunding.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for the append-only {@link LedgerEntryEntity} ledger. */
@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, Long> {

    List<LedgerEntryEntity> findByPartnerIdOrderByCreatedAtAscIdAsc(String partnerId);

    long countByPartnerId(String partnerId);
}
