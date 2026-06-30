package com.gme.pay.qr.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA entity for the {@code cpm_prepare_session} table (Flyway V003, WBS 5.3-T01).
 *
 * <p>One row per issued CPM token. {@code prefund_reserved_usd} is NUMERIC(20,8) — always
 * {@link BigDecimal} (MONEY_CONVENTION.md) and null for LOCAL partners / local-issuance fallback.
 */
@Entity
@Table(name = "cpm_prepare_session")
public class CpmPrepareSessionEntity {

    @Id
    @Column(name = "cpm_token_id", length = 64, nullable = false)
    private String cpmTokenId;

    @Column(name = "payment_id", length = 64, nullable = false)
    private String paymentId;

    @Column(name = "scheme_id", length = 32, nullable = false)
    private String schemeId;

    @Column(name = "direction", length = 10, nullable = false)
    private String direction;

    @Column(name = "country_code", length = 2, nullable = false)
    private String countryCode;

    @Column(name = "customer_ref", length = 255, nullable = false)
    private String customerRef;

    @Column(name = "partner_txn_ref", length = 64, nullable = false)
    private String partnerTxnRef;

    @Column(name = "prepare_token", length = 128, nullable = false)
    private String prepareToken;

    @Column(name = "qr_content", length = 512, nullable = false)
    private String qrContent;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "scheme_issued", nullable = false)
    private boolean schemeIssued;

    @Column(name = "prefund_reserved_usd", precision = 20, scale = 8)
    private BigDecimal prefundReservedUsd;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CpmPrepareSessionEntity() {
        // JPA only
    }

    public CpmPrepareSessionEntity(String cpmTokenId, String paymentId, String schemeId,
                                   String direction, String countryCode, String customerRef,
                                   String partnerTxnRef, String prepareToken, String qrContent,
                                   String status, boolean schemeIssued, BigDecimal prefundReservedUsd,
                                   Instant issuedAt, Instant expiresAt, Instant updatedAt) {
        this.cpmTokenId         = cpmTokenId;
        this.paymentId          = paymentId;
        this.schemeId           = schemeId;
        this.direction          = direction;
        this.countryCode        = countryCode;
        this.customerRef        = customerRef;
        this.partnerTxnRef      = partnerTxnRef;
        this.prepareToken       = prepareToken;
        this.qrContent          = qrContent;
        this.status             = status;
        this.schemeIssued       = schemeIssued;
        this.prefundReservedUsd = prefundReservedUsd;
        this.issuedAt           = issuedAt;
        this.expiresAt          = expiresAt;
        this.updatedAt          = updatedAt;
    }

    public String getCpmTokenId()          { return cpmTokenId; }
    public String getPaymentId()           { return paymentId; }
    public String getSchemeId()            { return schemeId; }
    public String getDirection()           { return direction; }
    public String getCountryCode()         { return countryCode; }
    public String getCustomerRef()         { return customerRef; }
    public String getPartnerTxnRef()       { return partnerTxnRef; }
    public String getPrepareToken()        { return prepareToken; }
    public String getQrContent()           { return qrContent; }
    public String getStatus()              { return status; }
    public boolean isSchemeIssued()        { return schemeIssued; }
    public BigDecimal getPrefundReservedUsd() { return prefundReservedUsd; }
    public Instant getIssuedAt()           { return issuedAt; }
    public Instant getExpiresAt()          { return expiresAt; }
    public Instant getUpdatedAt()          { return updatedAt; }

    public void setStatus(String status)        { this.status = status; }
    public void setUpdatedAt(Instant updatedAt)  { this.updatedAt = updatedAt; }
}
