package com.gme.pay.registry.ops;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The single global ops-control row (V038 {@code ops_control}, PK fixed at
 * {@code 1}). Holds the two platform-wide kill-switch flags — {@code systemPaused}
 * (hard master switch) and {@code maintenanceMode} (soft, degraded) — plus the
 * operator {@code reason}/{@code since} that back the shared
 * {@link com.gme.pay.contracts.OperationalStatusView} global fields.
 *
 * <p>There is always exactly one row (V038 seeds the all-clear singleton), so the
 * service reads it by PK and mutates it in place; no upsert race because the row
 * pre-exists.
 */
@Entity
@Table(name = "ops_control")
public class OpsControlEntity {

    /** Fixed singleton primary key. */
    public static final int SINGLETON_ID = 1;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    @Column(name = "system_paused", nullable = false)
    private boolean systemPaused;

    @Column(name = "maintenance_mode", nullable = false)
    private boolean maintenanceMode;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "since")
    private Instant since;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public OpsControlEntity() {
        // JPA
    }

    public Integer getId() {
        return id;
    }

    public boolean isSystemPaused() {
        return systemPaused;
    }

    public void setSystemPaused(boolean systemPaused) {
        this.systemPaused = systemPaused;
    }

    public boolean isMaintenanceMode() {
        return maintenanceMode;
    }

    public void setMaintenanceMode(boolean maintenanceMode) {
        this.maintenanceMode = maintenanceMode;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getSince() {
        return since;
    }

    public void setSince(Instant since) {
        this.since = since;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
