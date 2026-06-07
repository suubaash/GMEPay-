package com.gme.pay.events;

import java.time.Instant;

/** Base contract for all domain events. Concrete events live with their owning service. */
public interface DomainEvent {

    String eventType();

    String aggregateId();

    Instant occurredAt();
}
