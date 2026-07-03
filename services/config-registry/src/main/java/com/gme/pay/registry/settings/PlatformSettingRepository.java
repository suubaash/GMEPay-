package com.gme.pay.registry.settings;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link PlatformSettingEntity}, keyed by the setting {@code key}.
 * The generic store is small (a handful of tunables), so the list read is an
 * unpaged, key-sorted {@code findAll}.
 */
@Repository
public interface PlatformSettingRepository extends JpaRepository<PlatformSettingEntity, String> {

    /** All settings, key-sorted (the admin editor renders them in a stable order). */
    List<PlatformSettingEntity> findAllByOrderByKeyAsc();
}
