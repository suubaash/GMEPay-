package com.gme.pay.contracts.events;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Canonical payload for the {@code prefunding.deducted} domain event emitted when a partner's
 * prefunding balance is atomically debited to fund a payout, on topic
 * {@code gmepay.prefunding.deducted}. Mirrors the {@code POST /internal/v1/prefunding/{partnerId}/deduct}
 * result (balance + ledgerEntryId) so balance/statement consumers can project deductions without a
 * second call.
 *
 * <p><b>Convention.</b> camelCase fields; money rides as decimal STRINGs per
 * {@code docs/MONEY_CONVENTION.md}. {@code amountUsd} / {@code at} / {@code txnRef} deliberately match
 * {@link com.gme.pay.contracts.BalanceDeductionEntry} so the history view and this event share a shape.
 *
 * <ul>
 *   <li>{@code amountUsd} — USD amount deducted from the prefunding balance.</li>
 *   <li>{@code balanceAfterUsd} — resulting available balance after the debit.</li>
 *   <li>{@code ledgerEntryId} — id of the prefunding ledger row created (idempotency anchor).</li>
 *   <li>{@code idempotencyKey} — the deduct idempotency key (== reverse {@code txnRef}).</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PrefundDeductedPayload(
        String eventType,
        String aggregateId,
        Instant occurredAt,
        long partnerId,
        String txnRef,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amountUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balanceAfterUsd,
        String ledgerEntryId,
        String idempotencyKey,
        Instant at) {

    /** Canonical eventType string; drives topic {@code gmepay.prefunding.deducted}. */
    public static final String EVENT_TYPE = "prefunding.deducted";
}
