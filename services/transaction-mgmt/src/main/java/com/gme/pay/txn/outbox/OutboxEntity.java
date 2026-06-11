package com.gme.pay.txn.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity for the {@code outbox} table created by Flyway V002 — one row per pending
 * or published domain event.
 *
 * <p>Rows are written in the SAME database transaction as the business write that produced
 * the event (see {@link OutboxAppender}), then drained asynchronously by
 * {@link OutboxPublisher}. This is the transactional Outbox pattern: at-least-once delivery
 * without 2PC.
 *
 * <p>{@code publishedAt} stays {@code null} until the publisher has handed the event to
 * {@link com.gme.pay.events.EventPublisher} <em>successfully</em>; a publish failure leaves
 * it null so the next drain tick retries.
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
