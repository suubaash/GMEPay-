package com.gme.pay.registry.credential;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link PartnerCredentialEntity} (V028 ledger). */
public interface PartnerCredentialRepository
        extends JpaRepository<PartnerCredentialEntity, Long> {

    /** Full ledger of a partner, newest first (the admin list view). */
    List<PartnerCredentialEntity> findByPartnerIdOrderByIssuedAtDescIdDesc(Long partnerId);

    /** ACTIVE rows of a partner in one environment (issuance guard / rotation set). */
    List<PartnerCredentialEntity> findByPartnerIdAndEnvironmentAndStatus(
            Long partnerId, String environment, String status);

    /** Rotation sweep: ACTIVE credentials issued before the 11-month threshold. */
    List<PartnerCredentialEntity> findByStatusAndIssuedAtBefore(String status, Instant threshold);
}
