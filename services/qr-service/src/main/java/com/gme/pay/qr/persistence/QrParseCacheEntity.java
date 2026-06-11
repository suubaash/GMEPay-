package com.gme.pay.qr.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA entity for the {@code qr_parse_cache} table (Flyway V001, ticket 17.2-G04).
 *
 * <p>One row per distinct raw payload; primary key is the SHA-256 hex of the raw payload.
 * {@code encoded_amount} is NUMERIC(20,8) — always {@link BigDecimal}, never double
 * (MONEY_CONVENTION.md).
 */
@Entity
@Table(name = "qr_parse_cache")
public class QrParseCacheEntity {

    @Id
    @Column(name = "payload_hash", length = 64, nullable = false)
    private String payloadHash;

    @Column(name = "raw_payload", length = 512, nullable = false)
    private String rawPayload;

    @Column(name = "format_indicator", nullable = false)
    private int formatIndicator;

    @Column(name = "currency_code", length = 3, nullable = false)
    private String currencyCode;

    @Column(name = "merchant_name", length = 200, nullable = false)
    private String merchantName;

    @Column(name = "merchant_city", length = 100, nullable = false)
    private String merchantCity;

    @Column(name = "mcc", length = 8, nullable = false)
    private String mcc;

    @Column(name = "country_code", length = 2, nullable = false)
    private String countryCode;

    @Column(name = "mai_tag", nullable = false)
    private int maiTag;

    @Column(name = "merchant_id", length = 50, nullable = false)
    private String merchantId;

    @Column(name = "qr_code_id", length = 64, nullable = false)
    private String qrCodeId;

    @Column(name = "encoded_amount", precision = 20, scale = 8)
    private BigDecimal encodedAmount;

    @Column(name = "crc_verified", nullable = false)
    private boolean crcVerified;

    @Column(name = "parsed_at", nullable = false)
    private Instant parsedAt;

    protected QrParseCacheEntity() {
        // JPA only
    }

    public QrParseCacheEntity(String payloadHash, String rawPayload, int formatIndicator,
                              String currencyCode, String merchantName, String merchantCity,
                              String mcc, String countryCode, int maiTag, String merchantId,
                              String qrCodeId, BigDecimal encodedAmount, boolean crcVerified,
                              Instant parsedAt) {
        this.payloadHash     = payloadHash;
        this.rawPayload      = rawPayload;
        this.formatIndicator = formatIndicator;
        this.currencyCode    = currencyCode;
        this.merchantName    = merchantName;
        this.merchantCity    = merchantCity;
        this.mcc             = mcc;
        this.countryCode     = countryCode;
        this.maiTag          = maiTag;
        this.merchantId      = merchantId;
        this.qrCodeId        = qrCodeId;
        this.encodedAmount   = encodedAmount;
        this.crcVerified     = crcVerified;
        this.parsedAt        = parsedAt;
    }

    public String getPayloadHash()     { return payloadHash; }
    public String getRawPayload()      { return rawPayload; }
    public int getFormatIndicator()    { return formatIndicator; }
    public String getCurrencyCode()    { return currencyCode; }
    public String getMerchantName()    { return merchantName; }
    public String getMerchantCity()    { return merchantCity; }
    public String getMcc()             { return mcc; }
    public String getCountryCode()     { return countryCode; }
    public int getMaiTag()             { return maiTag; }
    public String getMerchantId()      { return merchantId; }
    public String getQrCodeId()        { return qrCodeId; }
    public BigDecimal getEncodedAmount() { return encodedAmount; }
    public boolean isCrcVerified()     { return crcVerified; }
    public Instant getParsedAt()       { return parsedAt; }
}
