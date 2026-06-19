package com.gme.pay.auth.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data repository for {@link UserRoleEntity} ({@code user_roles} table). */
public interface UserRoleRepository extends JpaRepository<UserRoleEntity, Long> {

    /** All non-revoked assignments for a principal (validity window filtered in the service layer). */
    List<UserRoleEntity> findByPrincipalIdAndRevokedAtIsNull(Long principalId);

    /** Every assignment (incl. revoked/expired) for a principal — assignment history. */
    List<UserRoleEntity> findByPrincipalId(Long principalId);

    /** Non-revoked assignments of a role (validity window filtered by the caller) — for userCount. */
    List<UserRoleEntity> findByRoleIdAndRevokedAtIsNull(Long roleId);
}
