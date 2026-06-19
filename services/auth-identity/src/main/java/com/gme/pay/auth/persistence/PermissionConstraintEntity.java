package com.gme.pay.auth.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA mapping for the {@code permission_constraints} table (V004__rbac_constraints.sql).
 *
 * <p>A typed, DB-driven constraint attached to a scope — a role, a permission, a
 * role↔permission grant, or a single user assignment. The constraint engine
 * (lib-errors {@code com.gme.pay.rbac.constraint}) evaluates these at request time
 * with cascading-AND semantics; adding or tightening a constraint is a DB write,
 * no code deploy.
 *
 * <p>{@code configJson} is a flat JSON object (e.g. {@code {"timezone":"Asia/Tokyo",
 * "startHour":"9"}}) stored as TEXT for Postgres+H2 parity — the engine reads it as a
 * String→String map, so jsonb querying is unnecessary. {@code tenantId} is {@code null}
 * for platform-global constraints, or the partner BIGINT surrogate for partner-scoped ones.
 */
@Entity
@Table(name = "permission_constraints")
public class PermissionConstraintEntity {

    /** What a constraint is attached to. Mirrors the {@code ck_perm_constraints_scope} CHECK. */
    public enum ScopeType { ROLE, PERMISSION, ROLE_PERMISSION, USER_ROLE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scope_type", length = 20, nullable = false)
    private String scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    /** TIME | LOCATION | AMOUNT | DATA_FILTER | APPROVAL — matches {@code ConstraintType}. */
    @Column(name = "constraint_type", length = 16, nullable = false)
    private String constraintType;

    @Column(name = "config_json", nullable = false, columnDefinition = "TEXT")
    private String configJson;

    /** NULL = platform-global; otherwise the partner surrogate id. */
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Default constructor required by JPA. */
    protected PermissionConstraintEntity() {
    }

    public PermissionConstraintEntity(ScopeType scopeType, Long scopeId, String constraintType,
                                      String configJson, Long tenantId, boolean active, Instant createdAt) {
        this.scopeType = scopeType.name();
        this.scopeId = scopeId;
        this.constraintType = constraintType;
        this.configJson = configJson;
        this.tenantId = tenantId;
        this.active = active;
        this.createdAt = createdAt;
    }

    /** Soft-delete: a deactivated constraint stops being evaluated but is retained for audit. */
    public void deactivate() {
        this.active = false;
    }

    public Long getId() {
        return id;
    }

    public String getScopeType() {
        return scopeType;
    }

    public Long getScopeId() {
        return scopeId;
    }

    public String getConstraintType() {
        return constraintType;
    }

    public String getConfigJson() {
        return configJson;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
