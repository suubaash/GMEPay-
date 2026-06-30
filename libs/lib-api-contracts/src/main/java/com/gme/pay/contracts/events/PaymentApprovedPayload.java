package com.gme.pay.contracts.events;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Canonical payload for the {@code payment.approved} domain event published by payment-executor on
 * topic {@code gmepay.payment.approved} (eventType drives {@code gmepay.<eventType>}).
 *
 * <p><b>Where this lives.</b> lib-events stays the Spring-free, Jackson-free base abstraction
 * ({@code DomainEvent} interface + {@code EventPublisher}); the canonical, serialization-annotated
 * payload DTOs live here in lib-api-contracts (which already carries jackson-annotations and the money
 * convention). A service maps its in-memory {@code DomainEvent} to this DTO for the wire.
 *
 * <p><b>Field-name convention.</b> Fields are <b>camelCase</b> to MATCH the live producer
 * (payment-executor {@code RestRevenueLedgerClient} / {@code PaymentOrchestrator}) and the live
 * consumer (revenue-ledger {@code PaymentApprovedEventHandler}, which reads {@code collectionMarginUsd},
 * {@code payoutMarginUsd}, {@code serviceChargeAmount}, {@code serviceChargeCcy}, {@code feeSharePct}).
 * NOTE: this intentionally differs from the snake_case wording in IR-txn-1 of _FLEET_STATUS.md —
 * camelCase is what the already-green code actually emits and reads.
 *
 * <ul>
 *   <li>{@code eventType} — always {@code "payment.approved"}; drives the Kafka topic.</li>
 *   <li>{@code aggregateId} / {@code txnRef} — transaction reference; consumer falls back
 *       aggregateId → record key when {@code txnRef} is absent.</li>
 *   <li>{@code occurredAt} — UTC instant the payment was approved.</li>
 *   <li>{@code revenueDate} — optional explicit revenue (KST) date; consumer falls back to
 *       {@code occurredAt}'s UTC date.</li>
 *   <li>Money fields ({@code collectionMarginUsd}, {@code payoutMarginUsd},
 *       {@code serviceChargeAmount}) ride as decimal STRINGs per {@code docs/MONEY_CONVENTION.md}.</li>
 *   <li>{@code feeSharePct} — GME's fraction of the net merchant fee, decimal string.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PaymentApprovedPayload(
        String eventType,
        String aggregateId,
        String txnRef,
        Instant occurredAt,
        java.time.LocalDate revenueDate,
        long partnerId,
        long schemeId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal collectionMarginUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal payoutMarginUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal serviceChargeAmount,
        String serviceChargeCcy,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal feeSharePct) {

    /** Canonical eventType string; drives topic {@code gmepay.payment.approved}. */
    public static final String EVENT_TYPE = "payment.approved";
}
