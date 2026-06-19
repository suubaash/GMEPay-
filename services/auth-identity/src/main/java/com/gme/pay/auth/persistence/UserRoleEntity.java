package com.gme.pay.auth.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA mapping for the {@code user_roles} table (V003__rbac_core.sql) — a temporal
 * role assignment to a principal, with optional expiry ({@code validTo}) for
 * time-boxed grants and a {@code grantedBy}/{@code revokedAt} assignment trail.
 *
 * <p>Lives alongside V002's {@code principal_roles} during the Expand phase
 * (ADR-013): permission resolution reads {@code user_roles}; {@code principal_roles}
 * is dropped only in a later Contract migration.
 */
@Entity
@Table(name = "user_roles")
public class UserRoleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "principal_id", nullable = false)
    private Long principalId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    /** NULL = no expiry (permanent grant). */
    @Column(name = "valid_to")
    private Instant validTo;

    @Column(name = "granted_by", length = 128, nullable = false)
    private String grantedBy;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    /** NULL = active; set when the grant is revoked early. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected UserRoleEntity() {
    }

    public UserRoleEntity(Long principalId, Long roleId, Long tenantId,
                          Instant validFrom, Instant validTo, String grantedBy, Instant grantedAt) {
        this.principalId = principalId;
        this.roleId = roleId;
        this.tenantId = tenantId;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.grantedBy = grantedBy;
        this.grantedAt = grantedAt;
    }

    /** True if the assignment is active at {@code at} (not revoked, within validity window). */
    public boolean isActiveAt(Instant at) {
        if (revokedAt != null) return false;
        if (validFrom != null && at.isBefore(validFrom)) return false;
        return validTo == null || at.isBefore(validTo);
    }

    public void revoke(Instant at) {
        this.revokedAt = at;
    }

    public Long getId() {
        return id;
    }

    public Long getPrincipalId() {
        return principalId;
    }

    public Long getRoleId() {
        return roleId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidTo() {
        return validTo;
    }

    public String getGrantedBy() {
        return grantedBy;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
