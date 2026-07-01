package com.gme.pay.contracts.events;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Canonical payload for the {@code ops.alert} domain event published by the
 * Operations-features wave (ops monitors) on topic {@code gmepay.ops.alert}
 * (eventType drives {@code gmepay.<eventType>}).
 *
 * <p><b>Where this lives.</b> lib-events stays the Spring-free, Jackson-free
 * base abstraction ({@code DomainEvent} interface + {@code EventPublisher}); the
 * canonical, serialization-annotated payload DTOs live here in
 * lib-api-contracts. A service maps its in-memory {@code DomainEvent} to this
 * DTO for the wire — same shape as {@link PaymentApprovedPayload}.
 *
 * <p><b>Field-name convention.</b> Fields are <b>camelCase</b> to match the
 * live producer/consumer convention already used by {@link PaymentApprovedPayload}.
 *
 * <ul>
 *   <li>{@code eventType} — always {@code "ops.alert"}; drives the Kafka topic
 *       {@code gmepay.ops.alert}.</li>
 *   <li>{@code alertType} — the class of condition raised, e.g.
 *       {@code STUCK_TXN} | {@code UNCERTAIN_AGED} | {@code FLOAT_LOW} |
 *       {@code WEBHOOK_BACKLOG} | {@code RECON_BREAK} | {@code DECLINE_SPIKE}.</li>
 *   <li>{@code severity} — {@code INFO} | {@code WARN} | {@code CRITICAL}.</li>
 *   <li>{@code subjectRef} — the txn / partner / batch id the alert concerns.</li>
 *   <li>{@code detail} — free-text human-readable detail.</li>
 *   <li>{@code occurredAt} — ISO-8601 instant STRING the condition was raised.</li>
 * </ul>
 *
 * <p>Any money / numeric context rides as decimal STRINGs (embedded in
 * {@code detail}) per {@code docs/MONEY_CONVENTION.md}.
 *
 * <p>{@code @JsonInclude(ALWAYS)} so {@code null} fields stay on the wire.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record OpsAlertPayload(
        String eventType,
        String alertType,
        String severity,
        String subjectRef,
        String detail,
        String occurredAt) {

    /** Canonical eventType string; drives topic {@code gmepay.ops.alert}. */
    public static final String EVENT_TYPE = "ops.alert";
}
