package com.gme.pay.scheme.zeropay.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Audit + idempotency journal for GME's ZeroPay prepaid float ({@code gme_scheme_balance_entry}). */
public interface GmeSchemeBalanceEntryRepository extends JpaRepository<GmeSchemeBalanceEntryEntity, Long> {

    /** Idempotency guard: has this (type, ref) already been applied? */
    boolean existsBySchemeCodeAndEntryTypeAndTxnRef(String schemeCode, String entryType, String txnRef);

    /** Most-recent entries first, for the balance-inquiry view. */
    List<GmeSchemeBalanceEntryEntity> findTop20BySchemeCodeOrderByIdDesc(String schemeCode);
}
