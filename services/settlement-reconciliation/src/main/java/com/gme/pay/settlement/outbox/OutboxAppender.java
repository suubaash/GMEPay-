package com.gme.pay.settlement.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.events.kafka.KafkaEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * The outbox-writing {@link EventPublisher}: appends a row to the {@code outbox} table INSIDE the
 * caller's database transaction (write half of the transactional Outbox pattern). The settlement
 * batch job injects this BY NAME ({@link #BEAN_NAME}) so the {@code @Primary} {@link KafkaEventPublisher}
 * cannot hijack the domain path (direct-to-Kafka from inside a txn would break atomicity).
 * Ported from transaction-mgmt; only {@link #BEAN_NAME} differs so both services can coexist.
 */
@Component(OutboxAppender.BEAN_NAME)
public class OutboxAppender implements EventPublisher {

    public static final String BEAN_NAME = "settlementOutboxAppender";

    private static final ObjectMapper PAYLOAD_MAPPER = KafkaEventPublisher.defaultObjectMapper();

    private final OutboxRepository repository;

    public OutboxAppender(OutboxRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public void publish(DomainEvent event) {
        Objects.requireNonNull(event, "event");
        String payload;
        try {
            payload = PAYLOAD_MAPPER.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize outbox payload for eventType=" + event.eventType()
                            + " aggregateId=" + event.aggregateId(), e);
        }
        repository.save(new OutboxEntity(
                event.aggregateId(), event.eventType(), payload, event.occurredAt()));
    }
}
