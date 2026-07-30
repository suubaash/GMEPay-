package com.gme.pay.payment.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository over {@code unscreened_payments} (Flyway V010, gap <b>T5-3</b>).
 *
 * <p>The increment is a JPQL {@code UPDATE} rather than a read-modify-write, so two concurrent payments
 * cannot lose a count to a last-writer-wins race, and rather than a vendor {@code ON CONFLICT} upsert so
 * the same code runs on PostgreSQL and on the H2 PostgreSQL-mode slices. {@code UnscreenedPaymentCounter}
 * owns the insert-on-first-occurrence / retry-the-update-on-race sequence around it.
 */
public interface UnscreenedPaymentRepository extends JpaRepository<UnscreenedPaymentEntity, Long> {

    /**
     * Atomically add one to an existing aggregate row and advance its last-seen anchors.
     *
     * <p>{@code flushAutomatically} + {@code clearAutomatically} are both load-bearing, not decoration.
     * A JPQL bulk {@code UPDATE} goes straight to the database and does NOT see or update the
     * first-level cache, so without them: (a) a pending {@code INSERT} from the race-loser path might
     * not have reached the database when the {@code UPDATE} runs, and (b) any entity already in the
     * persistence context would keep its <b>stale</b> {@code paymentCount} and hand a reader an
     * under-count — a bug in a table whose only job is to state a number honestly. The clear is cheap
     * here because the increment runs in its own short {@code REQUIRES_NEW} transaction (see
     * {@code UnscreenedPaymentCounter}), so there is no unrelated work to invalidate.
     *
     * @return 1 when the row existed and was incremented, 0 when it did not exist (caller inserts)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE UnscreenedPaymentEntity u
               SET u.paymentCount = u.paymentCount + 1,
                   u.lastSeenAt = :seenAt,
                   u.lastPaymentRef = :paymentRef
             WHERE u.gapDate = :gapDate
               AND u.reasonCode = :reasonCode
               AND u.partyRole = :partyRole
               AND u.providerId = :providerId
               AND u.partnerRef = :partnerRef
            """)
    int increment(@Param("gapDate") LocalDate gapDate,
                  @Param("reasonCode") String reasonCode,
                  @Param("partyRole") String partyRole,
                  @Param("providerId") String providerId,
                  @Param("partnerRef") String partnerRef,
                  @Param("seenAt") Instant seenAt,
                  @Param("paymentRef") String paymentRef);

    /** The aggregate row for one key, if it exists. */
    Optional<UnscreenedPaymentEntity> findByGapDateAndReasonCodeAndPartyRoleAndProviderIdAndPartnerRef(
            LocalDate gapDate, String reasonCode, String partyRole, String providerId, String partnerRef);

    /** Most recent aggregates first, bounded by the caller's page size. */
    @Query("""
            SELECT u FROM UnscreenedPaymentEntity u
            WHERE (:from IS NULL OR u.gapDate >= :from)
              AND (:reasonCode IS NULL OR u.reasonCode = :reasonCode)
            ORDER BY u.gapDate DESC, u.id DESC
            """)
    List<UnscreenedPaymentEntity> findRecent(@Param("from") LocalDate from,
                                             @Param("reasonCode") String reasonCode,
                                             Pageable pageable);

    /**
     * Total unscreened-party count from {@code from} onwards ({@code null} = all time). Returns
     * {@code null} when there are no rows, which callers must read as ZERO COUNTED, never as "screened".
     */
    @Query("""
            SELECT SUM(u.paymentCount) FROM UnscreenedPaymentEntity u
            WHERE (:from IS NULL OR u.gapDate >= :from)
            """)
    Long totalFrom(@Param("from") LocalDate from);
}
