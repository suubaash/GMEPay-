package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * Result of a prefunding RESERVE — the reservation handle plus the partner's balances after the hold.
 * {@code reservationId} is the handle the matching {@link PrefundingReleaseRequest} (or the hard
 * deduct) references. Money rides as decimal STRINGs per {@code docs/MONEY_CONVENTION.md}.
 *
 * <ul>
 *   <li>{@code availableUsd} — balance still available to reserve/deduct after this hold.</li>
 *   <li>{@code reservedUsd} — total currently reserved (held but not yet deducted).</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PrefundingReserveResponse(
        long partnerId,
        String reservationId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reservedAmountUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal availableUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reservedUsd) {
}
