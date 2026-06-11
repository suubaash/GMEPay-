package com.gme.pay.registry.document;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link DocumentEntity} ({@code partner_document},
 * V010).
 *
 * <p>Under SCD-6 (ADR-010) a re-upload supersedes the prior row of the same
 * {@code (partner, docType)} and inserts a fresh one, so "the partner's
 * documents" means the CURRENT set ({@code superseded_at IS NULL}) — at most
 * one current row per doc type. Historical rows stay in the table (and their
 * objects stay in the object-locked vault) for version-history viewing; no API
 * deletes anything.
 */
@Repository
public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {

    /**
     * The CURRENT document set for the given partner surrogate id, in stable
     * insertion (id) order. Served by {@code idx_partner_document_current}.
     */
    @Query("""
            select d from DocumentEntity d
            where d.partnerId = :partnerId
              and d.supersededAt is null
            order by d.id asc
            """)
    List<DocumentEntity> findCurrentByPartnerId(@Param("partnerId") Long partnerId);

    /**
     * The CURRENT row of one doc type for the partner — the row a re-upload
     * supersedes. At most one matches by construction (the paired write keeps
     * the invariant).
     */
    @Query("""
            select d from DocumentEntity d
            where d.partnerId = :partnerId
              and d.docType = :docType
              and d.supersededAt is null
            """)
    Optional<DocumentEntity> findCurrentByPartnerIdAndDocType(@Param("partnerId") Long partnerId,
                                                              @Param("docType") DocumentType docType);
}
