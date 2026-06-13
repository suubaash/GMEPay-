package com.gme.pay.registry.scheme;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link SchemeOperatingHoursEntity}
 * ({@code scheme_operating_hours}, V024). Read-only reference data — the seed
 * rows arrive with the migration and no service-layer write path exists.
 */
@Repository
public interface SchemeOperatingHoursRepository
        extends JpaRepository<SchemeOperatingHoursEntity, SchemeOperatingHoursEntity.Key> {

    /** The weekly schedule for one scheme, Monday(0) .. Sunday(6). */
    List<SchemeOperatingHoursEntity> findBySchemeIdOrderByWeekday(String schemeId);
}
