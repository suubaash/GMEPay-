package com.gme.pay.auth.persistence;

import java.io.Serializable;
import java.util.Objects;

/** Composite identity for {@link MenuPermissionEntity} ({@code menu_permissions} PK). */
public class MenuPermissionId implements Serializable {

    private Long menuId;
    private Long permissionId;

    public MenuPermissionId() {
    }

    public MenuPermissionId(Long menuId, Long permissionId) {
        this.menuId = menuId;
        this.permissionId = permissionId;
    }

    public Long getMenuId() {
        return menuId;
    }

    public Long getPermissionId() {
        return permissionId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MenuPermissionId that)) return false;
        return Objects.equals(menuId, that.menuId) && Objects.equals(permissionId, that.permissionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(menuId, permissionId);
    }
}
