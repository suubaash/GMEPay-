package com.gme.pay.scheme.sendmn.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Repository for SendMN payment attempts ({@code smn_payments}, keyed by TX_TOKEN_NO). */
public interface SmnPaymentRepository extends JpaRepository<SmnPaymentEntity, Long> {

    Optional<SmnPaymentEntity> findByTxTokenNo(String txTokenNo);

    /**
     * All attempts recorded for a hub partner reference, newest first (a hub retry after
     * a lost verify-qr response can create more than one attempt row per reference).
     */
    List<SmnPaymentEntity> findByHubReferenceOrderByIdDesc(String hubReference);

    /**
     * All attempts in a given status whose row was created inside {@code [fromInclusive, toExclusive)}.
     * Backs the read-only daily settlement query
     * ({@code GET /internal/scheme/sendmn/settlement/daily}) that settlement-reconciliation's
     * SENDMN three-way tie-out reads: this adapter's own record of what SendMN confirmed, with the
     * registered rate and the USD SETTLEMENT_AMOUNT it was computed at.
     */
    List<SmnPaymentEntity> findByStatusAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByIdAsc(
            SmnPaymentEntity.Status status, Instant fromInclusive, Instant toExclusive);
}
