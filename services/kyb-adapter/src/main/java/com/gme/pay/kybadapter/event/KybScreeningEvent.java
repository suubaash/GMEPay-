package com.gme.pay.kybadapter.event;

import com.gme.pay.events.DomainEvent;
import com.gme.pay.kyb.ScreeningResult;
import java.time.Instant;
import java.util.List;

/**
 * Domain event for one completed screening run, published to Kafka topic
 * {@code gmepay.kyb.screening} (ADR-001 topic naming: {@code gmepay.} +
 * {@link #eventType()}).
 *
 * <p>Keyed by {@code partnerCode} ({@link #aggregateId()}) so all screening
 * events for one partner land on the same partition in run order. The payload
 * carries the full screening verdict — consumers (reporting-compliance daily
 * rescreen ledger, notification-webhook compliance alerts) act on the event
 * without calling back into kyb-adapter.
 */
public record KybScreeningEvent(
        String partnerCode,
        String status,
        List<ScreeningResult.Hit> hits,
        String providerRef,
        Instant screenedAt) implements DomainEvent {

    /** Builds the event straight from the provider's {@link ScreeningResult}. */
    public static KybScreeningEvent of(String partnerCode, ScreeningResult result) {
        return new KybScreeningEvent(
                partnerCode,
                result.status() == null ? null : result.status().name(),
                result.hitList(),
                result.providerRef(),
                result.screenedAt());
    }

    @Override
    public String eventType() {
        return "kyb.screening";
    }

    @Override
    public String aggregateId() {
        return partnerCode;
    }

    @Override
    public Instant occurredAt() {
        return screenedAt;
    }
}
