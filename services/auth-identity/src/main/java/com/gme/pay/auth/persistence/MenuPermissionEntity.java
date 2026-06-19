package com.gme.pay.auth.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * JPA mapping for the {@code menu_permissions} join (V003__rbac_core.sql) — links a
 * menu to the permission(s) that make it visible. Composite PK {@code (menu_id, permission_id)}.
 */
@Entity
@Table(name = "menu_permissions")
@IdClass(MenuPermissionId.class)
public class MenuPermissionEntity {

    @Id
    @Column(name = "menu_id", nullable = false)
    private Long menuId;

    @Id
    @Column(name = "permission_id", nullable = false)
    private Long permissionId;

    protected MenuPermissionEntity() {
    }

    public MenuPermissionEntity(Long menuId, Long permissionId) {
        this.menuId = menuId;
        this.permissionId = permissionId;
    }

    public Long getMenuId() {
        return menuId;
    }

    public Long getPermissionId() {
        return permissionId;
    }
}
