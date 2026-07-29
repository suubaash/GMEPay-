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
 *
 * <p>T1-4: the payload carries the run's PROVENANCE ({@code providerId} /
 * {@code authoritative} / {@code caveat}) alongside the verdict, so a consumer
 * that files, alerts on or reports a screening cannot present a stub run as a
 * completed check. A {@code status} of {@code NOT_SCREENED_NO_PROVIDER} means
 * nothing was screened.
 */
public record KybScreeningEvent(
        String partnerCode,
        String status,
        List<ScreeningResult.Hit> hits,
        String providerRef,
        Instant screenedAt,
        String providerId,
        boolean authoritative,
        String caveat) implements DomainEvent {

    /** Builds the event straight from the provider's {@link ScreeningResult}. */
    public static KybScreeningEvent of(String partnerCode, ScreeningResult result) {
        return new KybScreeningEvent(
                partnerCode,
                result.status() == null ? null : result.status().name(),
                result.hitList(),
                result.providerRef(),
                result.screenedAt(),
                result.provenance().providerId(),
                result.authoritative(),
                result.caveat());
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
