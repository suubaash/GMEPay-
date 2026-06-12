package com.gme.pay.registry.commercial;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link ContractEntity}
 * ({@code partner_contract}, V021). Same current-row contract as
 * {@code PrefundingConfigRepository}: {@code superseded_at IS NULL} is the
 * partner's live contract; history stays for as-of inspection.
 */
@Repository
public interface ContractRepository extends JpaRepository<ContractEntity, Long> {

    /**
     * The CURRENT contract row for the given partner surrogate id.
     * Served by {@code idx_partner_contract_current}.
     */
    @Query("""
            select c from ContractEntity c
            where c.partnerId = :partnerId
              and c.supersededAt is null
            """)
    Optional<ContractEntity> findCurrentByPartnerId(@Param("partnerId") Long partnerId);
}
