package com.gme.pay.payment.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Repository over {@code payment_authorizations} (two-phase authorize/confirm, V003). */
public interface PaymentAuthorizationRepository
        extends JpaRepository<PaymentAuthorizationEntity, String> {

    /** Idempotent authorize: a repeat for the same (partner, partnerTxnRef) returns the prior row. */
    Optional<PaymentAuthorizationEntity> findByPartnerIdAndPartnerTxnRef(long partnerId, String partnerTxnRef);

    /**
     * Owner-scoped status lookup for GET /v1/payments/{id} (5.2-T16). Keying on BOTH paymentId and
     * partnerId means a payment owned by another partner returns empty here — the caller maps that to
     * 404 (not 403) so ownership is never leaked.
     */
    Optional<PaymentAuthorizationEntity> findByPaymentIdAndPartnerId(String paymentId, long partnerId);

    /**
     * Authorizations in {@code status} whose window lapsed before {@code cutoff} — the expiry
     * sweeper's work-list. Filtering on status AUTHORIZED means in-flight (CONFIRMING) and
     * already-terminal (CONFIRMED/UNCERTAIN/...) rows are never swept. Bounded via {@code pageable}.
     */
    List<PaymentAuthorizationEntity> findByStatusAndExpiresAtBefore(
            String status, Instant cutoff, Pageable pageable);

    /**
     * Atomic status claim: transition {@code from → to} for exactly one caller. Returns the number of
     * rows updated (1 = this caller won the claim, 0 = someone else already moved it). This is the
     * concurrency guard that makes confirm submit to the scheme at most once.
     */
    @Modifying
    @Query("update PaymentAuthorizationEntity a set a.status = :to "
            + "where a.authId = :authId and a.status = :from")
    int compareAndSetStatus(@Param("authId") String authId,
                            @Param("from") String from,
                            @Param("to") String to);

    /** Marks a terminal/confirmed outcome (status + wallet-charge ref + confirmedAt) in one update. */
    @Modifying
    @Query("update PaymentAuthorizationEntity a set a.status = :status, "
            + "a.walletChargeRef = :walletChargeRef, a.confirmedAt = :confirmedAt where a.authId = :authId")
    int markOutcome(@Param("authId") String authId,
                    @Param("status") String status,
                    @Param("walletChargeRef") String walletChargeRef,
                    @Param("confirmedAt") Instant confirmedAt);
}
