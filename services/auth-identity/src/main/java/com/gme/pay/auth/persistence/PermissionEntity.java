package com.gme.pay.auth.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA mapping for the {@code permissions} table (V003__rbac_core.sql).
 *
 * A permission is a {@code resource.action} capability (e.g.
 * {@code settlement.resolve_exception}). The catalogue is DB-driven — adding a
 * permission needs no code deploy. {@code tenantId} is {@code null} for
 * platform-global permissions, or the partner BIGINT surrogate for
 * partner-scoped ones.
 */
@Entity
@Table(name = "permissions")
public class PermissionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable {@code resource.action} code. Unique. */
    @Column(name = "code", length = 128, nullable = false, unique = true)
    private String code;

    @Column(name = "resource", length = 64, nullable = false)
    private String resource;

    @Column(name = "action", length = 64, nullable = false)
    private String action;

    @Column(name = "description", length = 255)
    private String description;

    /** NULL = platform-global; otherwise the partner surrogate id. */
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Default constructor required by JPA. */
    protected PermissionEntity() {
    }

    public PermissionEntity(String code, String resource, String action,
                            String description, Long tenantId, Instant createdAt) {
        this.code = code;
        this.resource = resource;
        this.action = action;
        this.description = description;
        this.tenantId = tenantId;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getResource() {
        return resource;
    }

    public String getAction() {
        return action;
    }

    public String getDescription() {
        return description;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
