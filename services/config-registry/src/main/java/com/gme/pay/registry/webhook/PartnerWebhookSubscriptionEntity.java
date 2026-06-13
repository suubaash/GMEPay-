package com.gme.pay.registry.webhook;

import com.gme.pay.contracts.WebhookSubscriptionView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * JPA-mapped row of the {@code partner_webhook_subscription} table (V030) —
 * Slice 8 Lane D: the registry-side record of a partner's webhook
 * subscription per credential environment.
 *
 * <h2>Storage model</h2>
 *
 * <p>NOT bitemporal (deliberate departure from the step 1-7 child
 * aggregates): this is operational wiring state, at most ONE row per
 * ({@code partner_id}, {@code environment}) (V030 UNIQUE), updated in place;
 * mutation history lives in the ADR-007 audit log via
 * {@link WebhookSubscriptionJson} snapshots.
 *
 * <h2>Security (SEC-09 §4)</h2>
 *
 * <p>{@link #signingSecretHash} holds only the SHA-256 hex digest of the
 * secret the notification-webhook service minted at provisioning time; there
 * is no plaintext anywhere at rest and no getter path that could reconstruct
 * it. {@link #toView()} deliberately omits even the hash.
 *
 * <h2>Identifier</h2>
 *
 * <p>Engine-managed identity ({@link GenerationType#IDENTITY}); nothing
 * outside this service joins on the surrogate. The cross-service endpoint
 * reference is the id-STRING {@link #endpointId}.
 */
@Entity
@Table(name = "partner_webhook_subscription")
public class PartnerWebhookSubscriptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** FK to {@code partners.id} (the V003/V004 BIGINT surrogate). */
    @Column(name = "partner_id", nullable = false, updatable = false)
    private Long partnerId;

    /** Credential environment (V030 CHECK roster: SANDBOX | LIVE); never NULL. */
    @Column(name = "environment", nullable = false, updatable = false, length = 10)
    private String environment;

    /** HTTPS receiver URL. */
    @Column(name = "url", nullable = false, length = 512)
    private String url;

    /** Comma-separated event types; NULL = "all events". */
    @Column(name = "event_types", columnDefinition = "TEXT")
    private String eventTypesCsv;

    /** notification-webhook endpoint id-string; NULL while DRAFT. */
    @Column(name = "endpoint_id", length = 40)
    private String endpointId;

    /** SHA-256 hex digest of the issued signing secret; NULL while DRAFT. */
    @Column(name = "signing_secret_hash", length = 64)
    private String signingSecretHash;

    /** When the signing secret was last issued/rotated; NULL while DRAFT. */
    @Column(name = "last_rotated_at")
    private Instant lastRotatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private WebhookSubscriptionStatus status = WebhookSubscriptionStatus.DRAFT;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public PartnerWebhookSubscriptionEntity() {
        // JPA
    }

    @PrePersist
    void onPersist() {
        // MICROS truncation: the stored TIMESTAMP must equal the in-memory
        // value on both PostgreSQL and H2 — same discipline as
        // PartnerEntity.onPersist (Slice 1 lesson).
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    /**
     * Adapt this row to the canonical {@link WebhookSubscriptionView} wire
     * DTO. The signing secret hash is intentionally NOT included.
     */
    public WebhookSubscriptionView toView() {
        return new WebhookSubscriptionView(
                id,
                environment,
                url,
                eventTypesFromCsv(eventTypesCsv),
                endpointId,
                status == null ? null : status.name(),
                lastRotatedAt,
                createdAt,
                updatedAt);
    }

    /** {@code null}/empty list (= "all events") is stored as SQL {@code NULL}. */
    public static String eventTypesToCsv(List<String> eventTypes) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            return null;
        }
        List<String> cleaned = eventTypes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }

    /** SQL {@code NULL} (= "all events") maps back to a {@code null} list. */
    public static List<String> eventTypesFromCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public Long getId() {
        return id;
    }

    public Long getPartnerId() {
        return partnerId;
    }

    public void setPartnerId(Long partnerId) {
        this.partnerId = partnerId;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getEventTypesCsv() {
        return eventTypesCsv;
    }

    public void setEventTypesCsv(String eventTypesCsv) {
        this.eventTypesCsv = eventTypesCsv;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public void setEndpointId(String endpointId) {
        this.endpointId = endpointId;
    }

    public String getSigningSecretHash() {
        return signingSecretHash;
    }

    public void setSigningSecretHash(String signingSecretHash) {
        this.signingSecretHash = signingSecretHash;
    }

    public Instant getLastRotatedAt() {
        return lastRotatedAt;
    }

    public void setLastRotatedAt(Instant lastRotatedAt) {
        this.lastRotatedAt = lastRotatedAt;
    }

    public WebhookSubscriptionStatus getStatus() {
        return status;
    }

    public void setStatus(WebhookSubscriptionStatus status) {
        this.status = status;
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
