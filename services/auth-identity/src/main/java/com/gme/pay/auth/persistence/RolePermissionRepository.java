package com.gme.pay.auth.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** Spring Data repository for {@link RolePermissionEntity} ({@code role_permissions}). */
public interface RolePermissionRepository
        extends JpaRepository<RolePermissionEntity, RolePermissionId> {

    List<RolePermissionEntity> findByRoleId(Long roleId);

    /** Bulk fetch for permission resolution across a principal's effective roles. */
    List<RolePermissionEntity> findByRoleIdIn(Collection<Long> roleIds);
}
