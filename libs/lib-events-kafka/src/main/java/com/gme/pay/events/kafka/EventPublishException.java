package com.gme.pay.events.kafka;

/**
 * Raised when a domain event could not be published to Kafka (serialization failure,
 * broker error, or send timeout).
 *
 * <p>Deliberately a {@link RuntimeException}: outbox-relay callers run
 * {@code publish(...)} inside their own transaction/loop and must <em>not</em> mark an
 * outbox row as published when this is thrown — the row stays pending and is retried.
 */
public class EventPublishException extends RuntimeException {

    public EventPublishException(String message) {
        super(message);
    }

    public EventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
