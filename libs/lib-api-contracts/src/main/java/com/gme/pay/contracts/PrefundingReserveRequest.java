package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * Request to RESERVE (soft-hold) a slice of a partner's prefunding balance at OVERSEAS CPM token
 * issuance, before the irreversible payout — qr-service issues a token, reserves the USD it will need,
 * and the reservation is later released on expiry/decline or converted to a hard deduct on approval.
 * Idempotent on {@code idempotencyKey} (qr-service uses the CPM token / session id).
 *
 * <p>Money rides as a decimal STRING per {@code docs/MONEY_CONVENTION.md}. Pairs with
 * {@link PrefundingReserveResponse}; the matching release reuses {@code idempotencyKey}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PrefundingReserveRequest(
        long partnerId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amountUsd,
        String idempotencyKey,
        String txnRef) {
}
