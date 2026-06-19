package com.gme.pay.auth.persistence;

import java.io.Serializable;
import java.util.Objects;

/** Composite identity for {@link RolePermissionEntity} ({@code role_permissions} PK). */
public class RolePermissionId implements Serializable {

    private Long roleId;
    private Long permissionId;

    public RolePermissionId() {
    }

    public RolePermissionId(Long roleId, Long permissionId) {
        this.roleId = roleId;
        this.permissionId = permissionId;
    }

    public Long getRoleId() {
        return roleId;
    }

    public Long getPermissionId() {
        return permissionId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RolePermissionId that)) return false;
        return Objects.equals(roleId, that.roleId) && Objects.equals(permissionId, that.permissionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roleId, permissionId);
    }
}
