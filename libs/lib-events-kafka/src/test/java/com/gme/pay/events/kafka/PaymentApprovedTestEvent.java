package com.gme.pay.events.kafka;

import com.gme.pay.events.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Test fixture: a money-carrying domain event shaped like a real GMEPay+ event.
 * A record so Jackson serializes the extra components ({@code amount}, {@code currency})
 * alongside the {@link DomainEvent} contract fields.
 */
record PaymentApprovedTestEvent(String eventType,
                                String aggregateId,
                                Instant occurredAt,
                                BigDecimal amount,
                                String currency) implements DomainEvent {

    static PaymentApprovedTestEvent sample() {
        return new PaymentApprovedTestEvent(
                "payment.approved",
                "txn-0001",
                Instant.parse("2026-06-10T08:30:00Z"),
                new BigDecimal("10.20"),
                "USD");
    }
}
