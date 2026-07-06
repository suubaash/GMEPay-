package com.gme.pay.events;

import java.time.Instant;

/** Base contract for all domain events. Concrete events live with their owning service. */
public interface DomainEvent {

    String eventType();

    /**
     * Schema version of this event's payload contract (CTO gate: every event on the bus is
     * versioned so consumers can detect incompatible producers). Bump when a field changes
     * meaning or is removed; purely additive fields do NOT need a bump. Stamped onto the wire
     * envelope by the Kafka publisher as {@code schemaVersion}.
     */
    default int schemaVersion() {
        return 1;
    }

    String aggregateId();

    Instant occurredAt();
}
