package com.gme.pay.auth.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Spring Data repository for {@link PrincipalEntity} ({@code principals} table). */
public interface PrincipalRepository extends JpaRepository<PrincipalEntity, Long> {

    Optional<PrincipalEntity> findByUsername(String username);
}
