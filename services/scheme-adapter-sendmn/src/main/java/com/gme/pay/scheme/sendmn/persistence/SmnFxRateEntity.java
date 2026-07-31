package com.gme.pay.scheme.sendmn.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A buy rate registered by SendMN via our partner-hosted FX rate endpoint
 * ({@code POST /partner-hosted/fx-rate}). The latest registered rate for a currency
 * pair is what Confirm's {@code FX_USD_BUY_RATE}/{@code SETTLEMENT_AMOUNT} must be
 * computed from — SendMN re-verifies server-side (error 307).
 */
@Entity
@Table(name = "smn_fx_rates")
public class SmnFxRateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SendMN-generated unique rate registration id — the idempotency key of registration. */
    @Column(name = "fx_ticker_no", nullable = false, unique = true, length = 64)
    private String fxTickerNo;

    /** Raw NOTICE_DATE string as sent by SendMN (format unconfirmed — open issue). */
    @Column(name = "notice_date", nullable = false, length = 32)
    private String noticeDate;

    /** MNT per USD buy rate, e.g. 3373.000000. */
    @Column(name = "rate", nullable = false, precision = 18, scale = 6)
    private BigDecimal rate;

    @Column(name = "local_cur_code", nullable = false, length = 3)
    private String localCurCode;

    @Column(name = "settlement_cur_code", nullable = false, length = 3)
    private String settlementCurCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SmnFxRateEntity() {
        // JPA
    }

    public SmnFxRateEntity(String fxTickerNo, String noticeDate, BigDecimal rate,
                           String localCurCode, String settlementCurCode) {
        this.fxTickerNo = fxTickerNo;
        this.noticeDate = noticeDate;
        this.rate = rate;
        this.localCurCode = localCurCode;
        this.settlementCurCode = settlementCurCode;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getFxTickerNo() { return fxTickerNo; }
    public String getNoticeDate() { return noticeDate; }
    public BigDecimal getRate() { return rate; }
    public String getLocalCurCode() { return localCurCode; }
    public String getSettlementCurCode() { return settlementCurCode; }
    public Instant getCreatedAt() { return createdAt; }
}
