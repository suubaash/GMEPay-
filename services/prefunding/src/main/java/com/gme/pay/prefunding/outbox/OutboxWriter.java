package com.gme.pay.prefunding.outbox;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Inserts {@link OutboxEntity} rows for domain events that need to be published asynchronously.
 *
 * <p><strong>Transactional contract:</strong> this component intentionally has NO
 * {@code @Transactional} annotation. It is meant to be called from within an existing
 * transaction (e.g. inside {@code PrefundingService.deduct}/{@code credit} via
 * {@link com.gme.pay.prefunding.alert.TierAlertEvaluator}) so the outbox INSERT commits
 * atomically with the business write. If the business work rolls back, the outbox row rolls
 * back with it — that is the whole point of the Outbox pattern.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository repository;

    public OutboxWriter(OutboxRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository required");
    }

    /**
     * Enqueue a domain event for asynchronous publishing.
     *
     * @param aggregateId business id of the aggregate that produced the event (partnerCode)
     * @param eventType   event type discriminator, e.g. {@code "prefunding.alert"} —
     *                    KafkaEventPublisher prefixes {@code gmepay.} so this lands on topic
     *                    {@code gmepay.prefunding.alert} (ADR-001)
     * @param payload     opaque event body (JSON); consumers parse based on {@code eventType}
     */
    public void enqueue(String aggregateId, String eventType, String payload) {
        Objects.requireNonNull(aggregateId, "aggregateId required");
        Objects.requireNonNull(eventType, "eventType required");
        Objects.requireNonNull(payload, "payload required");
        repository.save(new OutboxEntity(aggregateId, eventType, payload,
                Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }
}
