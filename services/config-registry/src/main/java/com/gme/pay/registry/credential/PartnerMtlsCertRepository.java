package com.gme.pay.registry.credential;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link PartnerMtlsCertEntity} (V027, SCD-6). */
public interface PartnerMtlsCertRepository extends JpaRepository<PartnerMtlsCertEntity, Long> {

    /** CURRENT cert bindings of a partner across both environments. */
    @Query("select c from PartnerMtlsCertEntity c"
            + " where c.partnerId = :partnerId and c.supersededAt is null"
            + " order by c.environment asc, c.id asc")
    List<PartnerMtlsCertEntity> findCurrentByPartnerId(@Param("partnerId") Long partnerId);

    /** CURRENT cert binding(s) of a partner in one environment. */
    @Query("select c from PartnerMtlsCertEntity c"
            + " where c.partnerId = :partnerId and c.environment = :environment"
            + " and c.supersededAt is null order by c.id asc")
    List<PartnerMtlsCertEntity> findCurrentByPartnerIdAndEnvironment(
            @Param("partnerId") Long partnerId, @Param("environment") String environment);
}
