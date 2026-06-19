package com.gme.pay.auth.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * JPA mapping for the {@code role_permissions} join (V003__rbac_core.sql) — which
 * permissions a role grants. Composite PK {@code (role_id, permission_id)}.
 */
@Entity
@Table(name = "role_permissions")
@IdClass(RolePermissionId.class)
public class RolePermissionEntity {

    @Id
    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Id
    @Column(name = "permission_id", nullable = false)
    private Long permissionId;

    /** NULL = platform-global grant; otherwise partner-scoped. */
    @Column(name = "tenant_id")
    private Long tenantId;

    protected RolePermissionEntity() {
    }

    public RolePermissionEntity(Long roleId, Long permissionId, Long tenantId) {
        this.roleId = roleId;
        this.permissionId = permissionId;
        this.tenantId = tenantId;
    }

    public Long getRoleId() {
        return roleId;
    }

    public Long getPermissionId() {
        return permissionId;
    }

    public Long getTenantId() {
        return tenantId;
    }
}
