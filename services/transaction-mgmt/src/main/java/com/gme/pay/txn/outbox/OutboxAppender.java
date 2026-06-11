package com.gme.pay.txn.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.events.kafka.KafkaEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * The outbox-writing {@link EventPublisher}: instead of shipping the event anywhere, it
 * appends a row to the {@code outbox} table <em>inside the caller's database transaction</em>.
 *
 * <p>This is the write half of the transactional Outbox pattern: the domain event is durable
 * iff the business change committed. {@link OutboxPublisher} drains the rows asynchronously
 * to the real transport (Kafka via lib-events-kafka, or a logging fallback locally).
 *
 * <p>Domain code (the state machine) injects this bean BY NAME ({@link #BEAN_NAME}) so the
 * {@code @Primary} {@code KafkaEventPublisher} that lib-events-kafka auto-configures cannot
 * hijack the domain path — direct-to-Kafka publishing from inside a business transaction
 * would break atomicity (event sent even if the transaction rolls back).
 *
 * <p>The payload is the event serialized with
 * {@link KafkaEventPublisher#defaultObjectMapper() the canonical event mapper}:
 * {@code BigDecimal} as plain decimal string (MONEY_CONVENTION.md) and {@code java.time}
 * values as ISO-8601 strings.
 */
@Component(OutboxAppender.BEAN_NAME)
public class OutboxAppender implements EventPublisher {

    public static final String BEAN_NAME = "transactionOutboxAppender";

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
