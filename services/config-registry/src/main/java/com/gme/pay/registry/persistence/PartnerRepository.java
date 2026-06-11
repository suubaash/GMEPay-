package com.gme.pay.registry.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link PartnerEntity}.
 *
 * <p>The primary key is the surrogate {@code id BIGINT} (V003, promoted to PK by
 * V004). Under SCD-6 (ADR-010) the same business code lives on multiple rows
 * over time (one current + N historicals), so the human-facing
 * {@code partner_code} cannot be the PK.
 *
 * <p>Read APIs come in two flavours:
 * <ul>
 *   <li><b>Current view</b> — {@link #findCurrentByPartnerCode(String)} and
 *       {@link #findAllCurrent()} return rows with {@code superseded_at IS NULL}.
 *       This is the default lookup for the hot path.</li>
 *   <li><b>As-of view</b> — {@link #findAsOf(String, Instant, Instant)} returns the
 *       row that was BOTH valid in business time at {@code validAt} AND recorded
 *       as the current row in transaction time at {@code recordedAt}. This is the
 *       regulator-defensible historical view.</li>
 * </ul>
 *
 * <p>{@link #existsByPartnerCode} returns true if ANY row carries the code
 * (current or historical); callers wanting "is there a current row" should use
 * {@link #findCurrentByPartnerCode} and check presence.
 */
@Repository
public interface PartnerRepository extends JpaRepository<PartnerEntity, Long> {

    /**
     * Find the CURRENT row (transaction-time "now") for the given business code.
     * Backed by the partial unique index {@code partners_current} so the read is
     * an index lookup, not a full scan.
     */
    @Query("""
            select p from PartnerEntity p
            where p.partnerCode = :partnerCode
              and p.supersededAt is null
            """)
    Optional<PartnerEntity> findCurrentByPartnerCode(@Param("partnerCode") String partnerCode);

    /**
     * Every currently-active partner (one row per code). Used by the Admin UI's
     * partner list and the BFF's listAll path — operators want the live view, not
     * the audit timeline.
     */
    @Query("""
            select p from PartnerEntity p
            where p.supersededAt is null
            order by p.partnerCode asc
            """)
    List<PartnerEntity> findAllCurrent();

    /** True if any row (current or historical) carries the given business code. */
    boolean existsByPartnerCode(String partnerCode);

    /**
     * Convenience: any row matching the code (current OR historical). Useful for
     * callers that just need to confirm the code has ever existed — for example
     * the create endpoint's duplicate check.
     */
    Optional<PartnerEntity> findByPartnerCode(String partnerCode);

    /**
     * Bitemporal as-of read: return the row that simultaneously satisfies both
     * temporal predicates.
     *
     * <ul>
     *   <li><b>Business time</b> — half-open {@code [valid_from, valid_to)}: lower
     *       bound inclusive, upper bound exclusive, {@code valid_to IS NULL} =
     *       open-ended.</li>
     *   <li><b>Transaction time</b> — half-open {@code [recorded_at, superseded_at)}:
     *       the row was current at the given {@code recordedAt} instant.</li>
     * </ul>
     *
     * The combination answers the regulator question "as of recording-time T, what
     * did we believe was true on business date D?".
     */
    @Query("""
            select p from PartnerEntity p
            where p.partnerCode = :partnerCode
              and p.validFrom <= :validAt
              and (p.validTo is null or p.validTo > :validAt)
              and p.recordedAt <= :recordedAt
              and (p.supersededAt is null or p.supersededAt > :recordedAt)
            """)
    Optional<PartnerEntity> findAsOf(@Param("partnerCode") String partnerCode,
                                     @Param("validAt") Instant validAt,
                                     @Param("recordedAt") Instant recordedAt);
}
