package com.gme.pay.settlement.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity for the {@code outbox} table created by Flyway V007 — one row per pending or published
 * settlement domain event. Written in the SAME transaction as the batch (see {@link OutboxAppender}),
 * drained asynchronously by {@link OutboxPublisher} (transactional Outbox pattern, at-least-once).
 * Ported from transaction-mgmt's proven outbox.
 */
@Entity
@Table(name = "outbox")
public class OutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "aggregate_id", length = 64)
    private String aggregateId;

    @Column(name = "event_type", length = 64)
    private String eventType;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    /** Required by JPA. */
    protected OutboxEntity() {
    }

    public OutboxEntity(String aggregateId, String eventType, String payload, Instant createdAt) {
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.publishedAt = null;
    }

    public Long getId() { return id; }

    public String getAggregateId() { return aggregateId; }

    public String getEventType() { return eventType; }

    public String getPayload() { return payload; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getPublishedAt() { return publishedAt; }

    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
}
