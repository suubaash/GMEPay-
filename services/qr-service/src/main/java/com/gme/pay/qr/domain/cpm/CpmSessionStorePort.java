package com.gme.pay.qr.domain.cpm;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port for persisting CPM prepare sessions (WBS 5.3-T01) within qr-service's own datastore.
 *
 * <p>The REST/domain layer talks only to this interface; the JPA adapter lives in
 * {@code com.gme.pay.qr.persistence}.
 */
public interface CpmSessionStorePort {

    /** {@code true} if the given partner_txn_ref has already been used. */
    boolean existsByPartnerTxnRef(String partnerTxnRef);

    /**
     * Persist a freshly issued session (status ISSUED).
     *
     * @param reservation the OVERSEAS prefunding hold carried on this session, or {@code null}
     *                    for LOCAL / no-prefunding / local-issuance sessions
     */
    void save(CpmToken token, String direction, String countryCode, String customerRef,
              boolean schemeIssued, PrefundReservation reservation);

    /** Look up a session by its platform payment id. */
    Optional<CpmToken> findByPaymentId(String paymentId);

    /**
     * Mark every ISSUED session whose {@code expires_at} is before {@code cutoff} as EXPIRED.
     *
     * @return the transitioned sessions (idempotent: already-expired rows are not returned again),
     *         each carrying the prefunding hold to release (handle null when none was reserved)
     */
    List<ExpiredSession> expireOverdue(Instant cutoff);

    /**
     * The prefunding reservation taken at OVERSEAS token issuance, persisted on the session.
     *
     * @param partnerId     the partner whose balance is held
     * @param reservationId the prefunding reservation handle
     * @param reservedUsd   the held USD amount
     */
    record PrefundReservation(long partnerId, String reservationId, BigDecimal reservedUsd) {}

    /**
     * A session that just transitioned to EXPIRED plus the prefunding hold (if any) to release.
     *
     * @param cpmTokenId    the expired token id (== the reserve idempotency key)
     * @param partnerId     the held partner, or {@code null} when nothing was reserved
     * @param reservationId the reservation handle, or {@code null}
     */
    record ExpiredSession(String cpmTokenId, Long partnerId, String reservationId) {}
}
