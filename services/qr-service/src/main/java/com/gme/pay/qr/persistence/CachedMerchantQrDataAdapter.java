package com.gme.pay.qr.persistence;

import com.gme.pay.qr.domain.cpm.MerchantQrDataPort;
import com.gme.pay.qr.exception.MerchantNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * DB-cache-backed implementation of {@link MerchantQrDataPort} (ticket 17.2-G04).
 *
 * <p>Resolves merchants from the local {@code merchant_resolution_cache} table. Until the
 * HTTP client for the merchant-qr-data service lands, a cache miss behaves exactly like a
 * remote 404 per the port contract: {@link MerchantNotFoundException}. Rows are written by
 * {@link #cache(String, MerchantQrDataPort.MerchantResolution)} (sync job / read-through).
 */
@Component
public class CachedMerchantQrDataAdapter implements MerchantQrDataPort {

    private final MerchantResolutionCacheRepository repository;

    public CachedMerchantQrDataAdapter(MerchantResolutionCacheRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public MerchantResolution resolve(String qrCodeId) {
        return repository.findById(qrCodeId)
                .map(e -> new MerchantResolution(
                        e.getMerchantId(), e.getMerchantName(), e.getSchemeId(), e.isActive()))
                .orElseThrow(() -> new MerchantNotFoundException(qrCodeId));
    }

    /**
     * Upsert a resolution into the local cache (one row per {@code qrCodeId}).
     *
     * @param qrCodeId   the QR code identifier the resolution belongs to
     * @param resolution the resolved merchant data; never null
     */
    @Transactional
    public void cache(String qrCodeId, MerchantResolution resolution) {
        repository.save(new MerchantResolutionCacheEntity(
                qrCodeId,
                resolution.merchantId(),
                resolution.merchantName(),
                resolution.schemeId(),
                resolution.active(),
                Instant.now()
        ));
    }
}
