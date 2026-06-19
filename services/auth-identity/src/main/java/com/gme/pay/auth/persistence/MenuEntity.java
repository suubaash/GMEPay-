package com.gme.pay.auth.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA mapping for the {@code menus} table (V003__rbac_core.sql) — the data-driven
 * navigation tree. A menu row is rendered iff the principal holds any permission
 * linked via {@code menu_permissions}. Hierarchy via the {@code parent_id} self-FK.
 * Adding a menu item is a DB insert — no code deploy.
 */
@Entity
@Table(name = "menus")
public class MenuEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", length = 64, nullable = false, unique = true)
    private String code;

    /** Parent menu id, or NULL for a top-level item. */
    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "label", length = 128, nullable = false)
    private String label;

    @Column(name = "route", length = 255)
    private String route;

    @Column(name = "icon", length = 64)
    private String icon;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** ADMIN (hub console) | PARTNER (partner portal). */
    @Column(name = "menu_type", length = 16, nullable = false)
    private String menuType;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MenuEntity() {
    }

    public MenuEntity(String code, Long parentId, String label, String route, String icon,
                      int sortOrder, String menuType, Long tenantId, boolean active, Instant createdAt) {
        this.code = code;
        this.parentId = parentId;
        this.label = label;
        this.route = route;
        this.icon = icon;
        this.sortOrder = sortOrder;
        this.menuType = menuType;
        this.tenantId = tenantId;
        this.active = active;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public Long getParentId() {
        return parentId;
    }

    public String getLabel() {
        return label;
    }

    public String getRoute() {
        return route;
    }

    public String getIcon() {
        return icon;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public String getMenuType() {
        return menuType;
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
