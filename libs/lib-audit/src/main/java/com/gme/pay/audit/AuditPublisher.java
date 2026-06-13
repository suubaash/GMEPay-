package com.gme.pay.audit;

/**
 * Publishes audit events out of the writing service to the audit fan-out
 * (Kafka topic {@code gmepay.audit.<aggregateType>}) per ADR-007.
 *
 * <p>Implementations are expected to be called <i>after</i> the audit row is persisted
 * to the hot DB but <i>inside</i> the same transaction (the production wiring uses the
 * lib-events outbox pattern so the topic message is dispatched only on commit). The
 * Slice 1 wiring uses an in-process publisher; the Kafka adapter ({@link KafkaAuditPublisher})
 * lands when Kafka is configured.
 *
 * <p>Lives at the same altitude as {@code EventPublisher} in {@code lib-events}: a
 * Spring-free contract so the production wiring is the consuming service's call.
 */
public interface AuditPublisher {

    /**
     * Emit the event to the audit fan-out. Must not throw on backpressure inside a
     * transactional write path; implementations log and continue rather than fail the
     * business write (Kafka producer is configured async + outbox-backed in production
     * so this is the natural behaviour anyway).
     */
    void publish(AuditEvent event);
}
