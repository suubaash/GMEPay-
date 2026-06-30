package com.gme.pay.qr.domain.cpm;

import java.time.Instant;

/**
 * Decoded CPM token payload (WBS 5.4-T11).
 *
 * <p>In CPM mode the customer presents a dynamic QR carrying a one-time scheme {@code prepare_token}
 * rather than a merchant identity. Decoding does not touch the merchant or qr_code tables.
 *
 * @param token     the opaque prepare token; never blank
 * @param schemeId  the resolved scheme identifier
 * @param issuedAt  decode timestamp (issuance time is not carried in the payload)
 * @param expiresAt issuedAt + the scheme token TTL
 */
public record CpmTokenPayload(String token, String schemeId, Instant issuedAt, Instant expiresAt) {

    /** Returns {@code true} once {@link #expiresAt} has passed. */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
