package com.gme.pay.notify.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity mapping the {@code webhook_endpoint} table (V003).
 *
 * <p>One row per registered partner webhook endpoint. {@link #eventTypesCsv} is a
 * comma-separated list of subscribed event types; {@code null} means "all events".
 * The HMAC signing secret is <strong>never</strong> persisted here (Vault only).
 *
 * <p>Plain JavaBean &mdash; no Lombok &mdash; per project conventions.
 */
@Entity
@Table(name = "webhook_endpoint")
public class WebhookEndpointEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "partner_id", nullable = false)
    private Long partnerId;

    @Column(name = "webhook_url", nullable = false, length = 512)
    private String webhookUrl;

    @Column(name = "event_types", columnDefinition = "TEXT")
    private String eventTypesCsv;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public WebhookEndpointEntity() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPartnerId() {
        return partnerId;
    }

    public void setPartnerId(Long partnerId) {
        this.partnerId = partnerId;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public String getEventTypesCsv() {
        return eventTypesCsv;
    }

    public void setEventTypesCsv(String eventTypesCsv) {
        this.eventTypesCsv = eventTypesCsv;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
