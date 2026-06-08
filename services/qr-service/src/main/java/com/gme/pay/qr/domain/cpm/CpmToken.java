package com.gme.pay.qr.domain.cpm;

import java.time.Instant;

/**
 * Immutable value object representing a generated CPM token (WBS 5.3-T07).
 *
 * @param cpmTokenId    unique token identifier stored in cpm_prepare_session
 * @param paymentId     platform payment identifier
 * @param prepareToken  opaque one-time token from the ZeroPay scheme adapter
 * @param qrContent     encoded QR payload the partner renders as a QR image
 * @param schemeId      resolved QR scheme identifier
 * @param partnerTxnRef partner's own reference echoed back
 * @param issuedAt      token creation timestamp
 * @param expiresAt     hard expiry timestamp (issuedAt + TTL)
 */
public record CpmToken(
        String cpmTokenId,
        String paymentId,
        String prepareToken,
        String qrContent,
        String schemeId,
        String partnerTxnRef,
        Instant issuedAt,
        Instant expiresAt
) {
    /** Returns {@code true} if the token has passed its {@link #expiresAt} timestamp. */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
