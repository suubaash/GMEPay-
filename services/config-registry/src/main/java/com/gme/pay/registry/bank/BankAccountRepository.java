package com.gme.pay.registry.bank;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link BankAccountEntity}
 * ({@code partner_bank_account}, V012).
 *
 * <p>Under SCD-6 (ADR-010) every bulk replace / verification supersedes the
 * prior rows and inserts fresh ones, so "the partner's bank accounts" always
 * means the CURRENT set: rows with {@code superseded_at IS NULL}. Historical
 * sets remain in the table for as-of inspection; no API deletes anything.
 */
@Repository
public interface BankAccountRepository extends JpaRepository<BankAccountEntity, Long> {

    /**
     * The CURRENT bank-account set for the given partner surrogate id, in
     * stable insertion (id) order. Served by
     * {@code idx_partner_bank_account_current}.
     */
    @Query("""
            select b from BankAccountEntity b
            where b.partnerId = :partnerId
              and b.supersededAt is null
            order by b.id asc
            """)
    List<BankAccountEntity> findCurrentByPartnerId(@Param("partnerId") Long partnerId);
}
