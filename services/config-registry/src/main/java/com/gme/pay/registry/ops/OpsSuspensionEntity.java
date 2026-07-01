package com.gme.pay.registry.ops;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One per-entity emergency suspension row (V038 {@code ops_suspension}). An
 * {@code entityType} (PARTNER / SCHEME / ROUTE — the shared
 * {@link com.gme.pay.contracts.OperationalStatusView} buckets) + {@code entityId}
 * (partner code / scheme id / route identifier) quarantined by an operator.
 *
 * <p>Suspensions are toggled via {@link #active} rather than deleted so an
 * unsuspend keeps the history intact. The operational-status read aggregates
 * only the rows with {@code active = true}.
 */
@Entity
@Table(name = "ops_suspension")
public class OpsSuspensionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "entity_type", nullable = false, length = 16)
    private String entityType;

    @Column(name = "entity_id", nullable = false, length = 200)
    private String entityId;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public OpsSuspensionEntity() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
