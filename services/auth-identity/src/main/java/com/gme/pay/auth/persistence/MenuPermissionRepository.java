package com.gme.pay.auth.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data repository for {@link MenuPermissionEntity} ({@code menu_permissions}). */
public interface MenuPermissionRepository
        extends JpaRepository<MenuPermissionEntity, MenuPermissionId> {

    List<MenuPermissionEntity> findByMenuId(Long menuId);
}
