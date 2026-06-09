package com.gme.pay.registry.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link PartnerEntity}. The primary key is the
 * business {@code partner_id} (VARCHAR(32)) — there is no surrogate.
 */
@Repository
public interface PartnerRepository extends JpaRepository<PartnerEntity, String> {
}
