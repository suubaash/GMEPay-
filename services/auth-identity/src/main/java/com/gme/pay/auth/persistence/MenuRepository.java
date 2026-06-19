package com.gme.pay.auth.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data repository for {@link MenuEntity} ({@code menus} table). */
public interface MenuRepository extends JpaRepository<MenuEntity, Long> {

    /** Active menus of a type (ADMIN | PARTNER), ordered for rendering. */
    List<MenuEntity> findByMenuTypeAndActiveTrueOrderBySortOrder(String menuType);
}
