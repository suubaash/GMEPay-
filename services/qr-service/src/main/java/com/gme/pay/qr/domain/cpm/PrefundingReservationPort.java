package com.gme.pay.qr.domain.cpm;

import java.math.BigDecimal;

/**
 * Anti-corruption port for the prefunding RESERVE / RELEASE soft-hold (Phase 2, IR-qr-3).
 *
 * <p>At OVERSEAS CPM token issuance qr-service soft-holds the USD it will need so a later payout
 * cannot overdraw the partner's prefunding balance. The hold is RELEASED on token expiry or decline.
 * Both calls are idempotent on the supplied {@code idempotencyKey} (the CPM token / session id).
 *
 * <p>The production implementation ({@link com.gme.pay.qr.prefunding.RestPrefundingReservationClient})
 * calls prefunding's {@code POST /internal/v1/prefunding/{partnerId}/reserve|release} and is
 * {@code @ConditionalOnProperty}-gated; when prefunding is not running (tests / no-prefunding runs)
 * {@link com.gme.pay.qr.prefunding.InMemoryPrefundingReservationFixture} provides a self-contained
 * fallback so the generate / expiry flow still works end-to-end.
 */
public interface PrefundingReservationPort {

    /**
     * Reserve (soft-hold) {@code amountUsd} of the partner's prefunding balance.
     *
     * @param partnerId      the partner whose balance is held
     * @param amountUsd      USD amount to reserve (positive)
     * @param idempotencyKey idempotency key (the CPM token / session id) — replays return the same hold
     * @param txnRef         partner transaction reference for trace
     * @return the reservation handle + post-hold balances
     * @throws com.gme.pay.qr.exception.QRParseException with
     *         {@link com.gme.pay.qr.exception.QRErrorCode#INSUFFICIENT_PREFUNDING} (→402) on overdraw
     */
    Reservation reserve(long partnerId, BigDecimal amountUsd, String idempotencyKey, String txnRef);

    /**
     * Release a previously taken reservation so the held USD returns to available. Idempotent: a
     * release for an unknown / already-released hold is a no-op.
     *
     * @param partnerId      the partner whose hold is released
     * @param reservationId  the reserve handle (may be null — {@code idempotencyKey} also identifies it)
     * @param idempotencyKey the reserve idempotency key (CPM token / session id)
     * @param reason         human-readable release reason (e.g. "CPM_EXPIRED")
     */
    void release(long partnerId, String reservationId, String idempotencyKey, String reason);

    /**
     * Outcome of a successful {@link #reserve}.
     *
     * @param reservationId the handle the matching release references
     * @param reservedUsd   the amount actually held by this reservation
     */
    record Reservation(String reservationId, BigDecimal reservedUsd) {}
}
