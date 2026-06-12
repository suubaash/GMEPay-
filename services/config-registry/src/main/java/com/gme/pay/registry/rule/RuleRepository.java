package com.gme.pay.registry.rule;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link RuleEntity} ({@code partner_rule},
 * V017).
 *
 * <p>Under SCD-6 (ADR-010) every step-6 save supersedes the prior set and
 * inserts a fresh one, so "the partner's rules" always means the CURRENT rows:
 * {@code superseded_at IS NULL}. Historical rows remain in the table for as-of
 * inspection; no API deletes anything.
 */
@Repository
public interface RuleRepository extends JpaRepository<RuleEntity, Long> {

    /**
     * The CURRENT rule set for the given partner surrogate id, in id order
     * (deterministic for the canonical audit snapshot). Served by
     * {@code idx_partner_rule_current}.
     */
    @Query("""
            select r from RuleEntity r
            where r.partnerId = :partnerId
              and r.supersededAt is null
            order by r.id
            """)
    List<RuleEntity> findCurrentByPartnerId(@Param("partnerId") Long partnerId);
}
