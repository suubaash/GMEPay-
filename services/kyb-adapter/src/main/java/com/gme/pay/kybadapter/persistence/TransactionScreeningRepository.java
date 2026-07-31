package com.gme.pay.kybadapter.persistence;

import com.gme.pay.kyb.PaymentParty;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@code transaction_screening} (gap T5-3).
 *
 * <p>The coverage queries below exist because a screening control that cannot answer "how many payments
 * went through unscreened, and why" is not auditable. Today every answer is "all of them, because no
 * provider is configured" — which is exactly the number that needs to be reportable rather than
 * assumed.
 */
@Repository
public interface TransactionScreeningRepository extends JpaRepository<TransactionScreeningEntity, Long> {

    /** The single current row for one party of one transaction (unique index {@code ux_txn_screening_txn_party}). */
    Optional<TransactionScreeningEntity> findByTxnRefAndParty(String txnRef, PaymentParty party);

    /** Every party screened for one transaction, oldest first. */
    List<TransactionScreeningEntity> findByTxnRefOrderByIdAsc(String txnRef);

    /**
     * Screening coverage over a window: how many parties fell into each unscreened reason, per partner.
     *
     * <p>A {@code null} reason is a COMPLETED screening; it is grouped alongside the causes rather than
     * filtered out, so the denominator is present in the same result set. A coverage report that showed
     * only failures would let a reader infer a total that was never measured.
     */
    @Query("SELECT r.partnerId AS partnerId, r.unscreenedReason AS unscreenedReason, COUNT(r) AS partyCount "
            + "FROM TransactionScreeningEntity r "
            + "WHERE r.recordedAt >= :from AND r.recordedAt < :to "
            + "AND (:partnerId IS NULL OR r.partnerId = :partnerId) "
            + "GROUP BY r.partnerId, r.unscreenedReason "
            + "ORDER BY r.partnerId ASC")
    List<CoverageRow> coverage(@Param("partnerId") String partnerId,
                               @Param("from") Instant from,
                               @Param("to") Instant to);

    /** One {@code (partner, reason)} bucket of {@link #coverage}. */
    interface CoverageRow {

        /** The partner, or {@code null} for wallet traffic that has none. */
        String getPartnerId();

        /** The cause, or {@code null} meaning the screening actually completed. */
        com.gme.pay.kyb.UnscreenedReason getUnscreenedReason();

        long getPartyCount();
    }
}
