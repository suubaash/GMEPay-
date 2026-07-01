package com.gme.pay.contracts.events;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Canonical payload for the {@code payment.reversed} domain event, published on topic
 * {@code gmepay.payment.reversed} (eventType drives {@code gmepay.<eventType>}).
 *
 * <p><b>Why this exists.</b> Emitted when a payment's terminal outcome becomes {@code REVERSED} —
 * including an operator force-resolve of an {@code UNCERTAIN} txn to {@code REVERSED}. Today that
 * transition emits no domain event, so the held prefund float is released and no reversing journal is
 * booked: money moves with no ledger impact. This contract lets revenue-ledger book a reversing
 * journal and prefunding release the held float on the same signal.
 *
 * <p><b>Where this lives.</b> lib-events stays the Spring-free, Jackson-free base abstraction
 * ({@code DomainEvent} interface + {@code EventPublisher}); the canonical, serialization-annotated
 * payload DTOs live here in lib-api-contracts (which already carries jackson-annotations and the money
 * convention). A service maps its in-memory {@code DomainEvent} to this DTO for the wire.
 *
 * <p><b>Field-name convention.</b> Fields are <b>camelCase</b>, mirroring
 * {@link PaymentApprovedPayload}. Money fields ({@code reversedAmount}, {@code reversedUsd}) ride as
 * decimal STRINGs per {@code docs/MONEY_CONVENTION.md}.
 *
 * <ul>
 *   <li>{@code eventType} — always {@code "payment.reversed"}; drives the Kafka topic.</li>
 *   <li>{@code txnRef} — transaction reference being reversed.</li>
 *   <li>{@code partnerId} / {@code schemeId} — owning partner and scheme.</li>
 *   <li>{@code reversedAmount} / {@code currency} — reversed transaction amount and its currency.</li>
 *   <li>{@code reversedUsd} — the prefund USD to release; nullable when not applicable.</li>
 *   <li>{@code reason} — human/operator-supplied reason for the reversal.</li>
 *   <li>{@code source} — origin of the reversal, e.g. {@code OPERATOR}, {@code SCHEME},
 *       {@code TIMEOUT}.</li>
 *   <li>{@code occurredAt} — ISO-8601 instant the reversal became terminal.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PaymentReversedPayload(
        String eventType,
        String txnRef,
        String partnerId,
        String schemeId,
        String reversedAmount,
        String currency,
        String reversedUsd,
        String reason,
        String source,
        String occurredAt) {

    /** Canonical eventType string; drives topic {@code gmepay.payment.reversed}. */
    public static final String EVENT_TYPE = "payment.reversed";
}
