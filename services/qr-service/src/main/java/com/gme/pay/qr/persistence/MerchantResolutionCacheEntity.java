package com.gme.pay.qr.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code merchant_resolution_cache} table (Flyway V002, ticket 17.2-G04).
 *
 * <p>Local cache of merchant lookups normally served by the merchant-qr-data service.
 * One row per {@code qr_code_id}.
 */
@Entity
@Table(name = "merchant_resolution_cache")
public class MerchantResolutionCacheEntity {

    @Id
    @Column(name = "qr_code_id", length = 64, nullable = false)
    private String qrCodeId;

    @Column(name = "merchant_id", length = 50, nullable = false)
    private String merchantId;

    @Column(name = "merchant_name", length = 200, nullable = false)
    private String merchantName;

    @Column(name = "scheme_id", length = 20, nullable = false)
    private String schemeId;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "resolved_at", nullable = false)
    private Instant resolvedAt;

    protected MerchantResolutionCacheEntity() {
        // JPA only
    }

    public MerchantResolutionCacheEntity(String qrCodeId, String merchantId, String merchantName,
                                         String schemeId, boolean active, Instant resolvedAt) {
        this.qrCodeId     = qrCodeId;
        this.merchantId   = merchantId;
        this.merchantName = merchantName;
        this.schemeId     = schemeId;
        this.active       = active;
        this.resolvedAt   = resolvedAt;
    }

    public String getQrCodeId()     { return qrCodeId; }
    public String getMerchantId()   { return merchantId; }
    public String getMerchantName() { return merchantName; }
    public String getSchemeId()     { return schemeId; }
    public boolean isActive()       { return active; }
    public Instant getResolvedAt()  { return resolvedAt; }
}
