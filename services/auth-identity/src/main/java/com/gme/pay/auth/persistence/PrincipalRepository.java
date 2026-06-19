package com.gme.pay.auth.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Spring Data repository for {@link PrincipalEntity} ({@code principals} table). */
public interface PrincipalRepository extends JpaRepository<PrincipalEntity, Long> {

    Optional<PrincipalEntity> findByUsername(String username);

    /** Ids of principals holding a role via the direct {@code principal_roles} join (for userCount). */
    @Query("select p.id from PrincipalEntity p join p.roles r where r.id = :roleId")
    List<Long> findIdsByRoleId(@Param("roleId") Long roleId);
}
