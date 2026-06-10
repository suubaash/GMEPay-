package com.gme.pay.ledger.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity for the {@code outbox} table — one row per pending or published domain event.
 *
 * <p>Rows are written in the SAME transaction as the business write that produced the event
 * (e.g. a journal save), and then drained asynchronously by {@link OutboxPublisher}. This is
 * the transactional Outbox pattern: at-least-once delivery without 2PC.
 *
 * <p>Mapping mirrors {@code V003__create_outbox.sql}. {@code publishedAt} is {@code null} until
 * the publisher has handed the event to {@link com.gme.pay.events.EventPublisher} successfully;
 * partial-success failures leave it null so the next poll tick retries.
 */
@Entity
@Table(name = "outbox")
public class OutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "aggregate_id", length = 64, nullable = false)
    private String aggregateId;

    @Column(name = "event_type", length = 64, nullable = false)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    /** Required by JPA. */
    protected OutboxEntity() {
    }

    public OutboxEntity(String aggregateId, String eventType, String payload, Instant createdAt) {
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId required");
        this.eventType = Objects.requireNonNull(eventType, "eventType required");
        this.payload = Objects.requireNonNull(payload, "payload required");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt required");
        this.publishedAt = null;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }
}
