package com.gme.pay.notify.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity mapping the {@code webhook_dlq} table (V002).
 *
 * <p>A row is inserted when a delivery exhausts the {@code RetryPolicy} and is
 * promoted to the dead-letter queue. {@link #originalId} references the
 * {@code webhook_delivery_log.id} that failed, allowing Ops to trace the
 * full attempt history.
 */
@Entity
@Table(name = "webhook_dlq")
public class WebhookDlqEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "original_id", nullable = false)
    private Long originalId;

    @Column(name = "webhook_id", nullable = false, length = 64)
    private String webhookId;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    public WebhookDlqEntity() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOriginalId() {
        return originalId;
    }

    public void setOriginalId(Long originalId) {
        this.originalId = originalId;
    }

    public String getWebhookId() {
        return webhookId;
    }

    public void setWebhookId(String webhookId) {
        this.webhookId = webhookId;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    public void setAddedAt(Instant addedAt) {
        this.addedAt = addedAt;
    }
}
