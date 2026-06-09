package com.gme.pay.merchant.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * MongoDB document representing the persisted merchant projection for the
 * merchant-qr-data service (WBS 9.3, Phase-1 persistence).
 *
 * <p>This is the storage-layer model and is intentionally decoupled from the
 * domain {@code Merchant} record returned by the REST API. The adapter
 * {@link MongoBackedMerchantRepository} bridges between this document and the
 * domain model.
 *
 * <p>Persisted in the {@code merchants} collection of the Mongo {@code merchant}
 * database (owned exclusively by this service per the MSA contract).
 */
@Document(collection = "merchants")
public class MerchantDocument {

    /** Mongo primary key. Currently the QR code value is used as the natural id. */
    @Id
    private String id;

    /** Business merchant identifier (ZeroPay CHAR(10)). */
    private String merchantId;

    /** QR code identifier associated with this merchant projection (ZeroPay CHAR(20)). */
    private String qrCode;

    /** Human-readable merchant name. */
    private String name;

    /** ISO-3166 country code where the merchant operates (e.g. {@code KR}). */
    private String country;

    /** ISO-4217 settlement currency for this merchant (e.g. {@code KRW}). */
    private String settleCurrency;

    /** True when the merchant is currently active for payment acceptance. */
    private boolean active;

    public MerchantDocument() {
        // required by Spring Data Mongo
    }

    public MerchantDocument(
            String id,
            String merchantId,
            String qrCode,
            String name,
            String country,
            String settleCurrency,
            boolean active) {
        this.id = id;
        this.merchantId = merchantId;
        this.qrCode = qrCode;
        this.name = name;
        this.country = country;
        this.settleCurrency = settleCurrency;
        this.active = active;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    public String getQrCode() {
        return qrCode;
    }

    public void setQrCode(String qrCode) {
        this.qrCode = qrCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getSettleCurrency() {
        return settleCurrency;
    }

    public void setSettleCurrency(String settleCurrency) {
        this.settleCurrency = settleCurrency;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
