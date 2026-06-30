package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request to RELEASE a prefunding reservation taken by {@link PrefundingReserveRequest} — called on
 * CPM token expiry or a declined/abandoned payment so the soft-held USD returns to available. Idempotent
 * on {@code idempotencyKey} (reuse the reserve key), so a release that already ran is a no-op.
 *
 * <p>Reuses the reserve handle via {@code reservationId}; either {@code reservationId} or
 * {@code idempotencyKey} identifies the hold to release.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PrefundingReleaseRequest(
        long partnerId,
        String reservationId,
        String idempotencyKey,
        String reason) {
}
