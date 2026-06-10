package com.gme.pay.qr.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link MerchantResolutionCacheEntity}; PK is qr_code_id. */
public interface MerchantResolutionCacheRepository
        extends JpaRepository<MerchantResolutionCacheEntity, String> {
}
