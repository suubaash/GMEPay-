package com.gme.pay.notify.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity mapping the {@code webhook_endpoint} table (V003 + V004).
 *
 * <p>One row per registered partner webhook endpoint per environment
 * ({@code SANDBOX} | {@code LIVE}, V004). {@link #eventTypesCsv} is a
 * comma-separated list of subscribed event types; {@code null} means "all events".
 * The HMAC signing secret is <strong>never</strong> persisted in plaintext:
 * V004 rows generated through the Slice 8 registration endpoint carry only its
 * SHA-256 digest in {@link #signingSecretHash} (the plaintext is returned once
 * at registration and routed to Vault for dispatch-time signing); legacy V003
 * rows are Vault-only and keep a {@code null} hash.
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

    /** Credential environment (V004 CHECK roster: SANDBOX | LIVE); never NULL. */
    @Column(name = "environment", nullable = false, length = 10)
    private String environment = "SANDBOX";

    /**
     * SHA-256 digest (lowercase hex) of the generated signing secret; the
     * plaintext is never stored. NULL on legacy V003 (Vault-only) rows.
     */
    @Column(name = "signing_secret_hash", length = 64)
    private String signingSecretHash;

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

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getSigningSecretHash() {
        return signingSecretHash;
    }

    public void setSigningSecretHash(String signingSecretHash) {
        this.signingSecretHash = signingSecretHash;
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
