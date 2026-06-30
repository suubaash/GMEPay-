package com.gme.pay.qr.domain.cpm;

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

    /** Persist a freshly issued session (status ISSUED). */
    void save(CpmToken token, String direction, String countryCode, String customerRef,
              boolean schemeIssued);

    /** Look up a session by its platform payment id. */
    Optional<CpmToken> findByPaymentId(String paymentId);

    /**
     * Mark every ISSUED session whose {@code expires_at} is before {@code cutoff} as EXPIRED.
     *
     * @return the cpm_token_ids that transitioned to EXPIRED (idempotent: already-expired rows are
     *         not returned again)
     */
    List<String> expireOverdue(Instant cutoff);
}
