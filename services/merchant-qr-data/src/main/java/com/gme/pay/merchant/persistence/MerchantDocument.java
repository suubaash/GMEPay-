package com.gme.pay.merchant.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * MongoDB document representing the persisted merchant projection for the
 * merchant-qr-data service (WBS 9.3 / 17.7-G01, ADR-003: MongoDB).
 *
 * <p>This is the storage-layer model and is intentionally decoupled from the
 * domain {@code Merchant} record returned by the REST API. The adapter
 * {@link MongoBackedMerchantRepository} bridges between this document and the
 * domain model.
 *
 * <p>Persisted in the {@code merchants} collection (owned exclusively by this
 * service per the MSA contract), keyed by the QR code identifier: the QR code
 * is the natural {@code _id}, and {@link MongoPersistenceConfig} additionally
 * ensures a unique index on the {@code qrCode} field used by the lookup query.
 */
@Document(collection = "merchants")
public class MerchantDocument {

    /** Mongo primary key — the QR code value is used as the natural id. */
    @Id
    private String id;

    /** Business merchant identifier (ZeroPay CHAR(10)). */
    private String merchantId;

    /** QR code identifier associated with this merchant projection (ZeroPay CHAR(20)). */
    private String qrCode;

    /** Human-readable merchant name. */
    private String name;

    /** Merchant category/type (e.g. {@code RETAIL}, {@code FOOD_BEVERAGE}). */
    private String merchantType;

    /** Fee scheme classification (e.g. {@code DOMESTIC}, {@code CROSSBORDER}). */
    private String feeType;

    /** Lifecycle status (e.g. {@code ACTIVE}, {@code SUSPENDED}, {@code DEACTIVATED}). */
    private String status;

    /** True when the merchant is currently active for payment acceptance. */
    private boolean active;

    /** ISO 4217 payout currency (e.g. {@code KRW}). Required by payment-executor RestQrClient. */
    private String payoutCurrency;

    /** Scheme identifier routing payments (e.g. {@code ZEROPAY}). Required by payment-executor RestQrClient. */
    private String schemeId;

    /** City / locality of the merchant outlet. */
    private String city;

    /** ISO 18245 Merchant Category Code (4-digit string, e.g. {@code 5411}). */
    private String mcc;

    public MerchantDocument() {
        // required by Spring Data Mongo
    }

    public MerchantDocument(
            String id,
            String merchantId,
            String qrCode,
            String name,
            String merchantType,
            String feeType,
            String status,
            boolean active) {
        this.id = id;
        this.merchantId = merchantId;
        this.qrCode = qrCode;
        this.name = name;
        this.merchantType = merchantType;
        this.feeType = feeType;
        this.status = status;
        this.active = active;
    }

    public MerchantDocument(
            String id,
            String merchantId,
            String qrCode,
            String name,
            String merchantType,
            String feeType,
            String status,
            boolean active,
            String payoutCurrency,
            String schemeId,
            String city,
            String mcc) {
        this(id, merchantId, qrCode, name, merchantType, feeType, status, active);
        this.payoutCurrency = payoutCurrency;
        this.schemeId = schemeId;
        this.city = city;
        this.mcc = mcc;
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

    public String getMerchantType() {
        return merchantType;
    }

    public void setMerchantType(String merchantType) {
        this.merchantType = merchantType;
    }

    public String getFeeType() {
        return feeType;
    }

    public void setFeeType(String feeType) {
        this.feeType = feeType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getPayoutCurrency() {
        return payoutCurrency;
    }

    public void setPayoutCurrency(String payoutCurrency) {
        this.payoutCurrency = payoutCurrency;
    }

    public String getSchemeId() {
        return schemeId;
    }

    public void setSchemeId(String schemeId) {
        this.schemeId = schemeId;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getMcc() {
        return mcc;
    }

    public void setMcc(String mcc) {
        this.mcc = mcc;
    }
}
