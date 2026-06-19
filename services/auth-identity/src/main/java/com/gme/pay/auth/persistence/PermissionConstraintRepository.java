package com.gme.pay.auth.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data repository for {@link PermissionConstraintEntity} ({@code permission_constraints} table). */
public interface PermissionConstraintRepository extends JpaRepository<PermissionConstraintEntity, Long> {

    /** Active constraints attached to a given scope row (e.g. all active constraints on a role). */
    List<PermissionConstraintEntity> findByScopeTypeAndScopeIdAndActiveTrue(String scopeType, Long scopeId);

    /** Active constraints for several scope rows of one type — batch lookup during resolution. */
    List<PermissionConstraintEntity> findByScopeTypeAndScopeIdInAndActiveTrue(String scopeType, List<Long> scopeIds);

    /** All constraints on a scope row incl. deactivated — admin listing / audit. */
    List<PermissionConstraintEntity> findByScopeTypeAndScopeId(String scopeType, Long scopeId);
}
