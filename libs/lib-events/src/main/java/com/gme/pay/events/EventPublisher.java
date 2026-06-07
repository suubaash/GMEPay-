package com.gme.pay.events;

/**
 * Publishes domain events. Phase 1 implementation writes to the transactional Outbox table;
 * at integration this is backed by Kafka without changing callers.
 */
public interface EventPublisher {

    void publish(DomainEvent event);
}
