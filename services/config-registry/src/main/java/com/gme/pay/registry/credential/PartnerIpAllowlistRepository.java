package com.gme.pay.registry.credential;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link PartnerIpAllowlistEntity} (V026). */
public interface PartnerIpAllowlistRepository
        extends JpaRepository<PartnerIpAllowlistEntity, Long> {

    /** Full allowlist of a partner across both environments, stable order. */
    List<PartnerIpAllowlistEntity> findByPartnerIdOrderByEnvironmentAscCidrAsc(Long partnerId);

    /** Environment-scoped allowlist (the gateway's edge-check shape). */
    List<PartnerIpAllowlistEntity> findByPartnerIdAndEnvironmentOrderByCidrAsc(
            Long partnerId, String environment);

    /** Bulk-replace first half: clear the partner's whole allowlist. */
    void deleteByPartnerId(Long partnerId);
}
