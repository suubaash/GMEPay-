package com.gme.pay.registry.contact;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link ContactEntity} ({@code partner_contact},
 * V009).
 *
 * <p>Under SCD-6 (ADR-010) every bulk replace supersedes the prior rows and
 * inserts fresh ones, so "the partner's contacts" always means the CURRENT set:
 * rows with {@code superseded_at IS NULL}. Historical sets remain in the table
 * for as-of inspection; no API deletes anything.
 */
@Repository
public interface ContactRepository extends JpaRepository<ContactEntity, Long> {

    /**
     * The CURRENT contact set for the given partner surrogate id, in stable
     * insertion (id) order. Served by {@code idx_partner_contact_current}.
     */
    @Query("""
            select c from ContactEntity c
            where c.partnerId = :partnerId
              and c.supersededAt is null
            order by c.id asc
            """)
    List<ContactEntity> findCurrentByPartnerId(@Param("partnerId") Long partnerId);
}
