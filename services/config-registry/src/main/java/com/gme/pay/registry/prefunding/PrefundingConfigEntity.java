package com.gme.pay.registry.prefunding;

import com.gme.pay.contracts.PrefundingConfigView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * JPA-mapped row of the {@code partner_prefunding_config} table (V015) — the
 * prefunding parameters of a partner, bitemporally versioned per ADR-010
 * (Slice 5).
 *
 * <h2>Bitemporal storage</h2>
 *
 * <p>Same SCD-6 discipline as {@code PartnerEntity} (V004) and
 * {@code SettlementConfigEntity} (V013): rows are NEVER UPDATEd in place. A
 * wizard step-5 save is a paired write — the current row gets
 * {@code superseded_at = now} and a fresh row is INSERTed with
 * {@code recorded_at = now}, both halves sharing one MICROS-truncated instant
 * (see {@link PrefundingConfigService}).
 *
 * <h2>Money</h2>
 *
 * <p>All {@code *_usd} fields are {@link BigDecimal} in major USD units
 * (NUMERIC(19,4) per {@code docs/MONEY_CONVENTION.md}); the service normalises
 * to scale 4 before persisting so the stored value equals the in-memory value
 * on both PostgreSQL and H2 (the same stored-equals-in-memory discipline the
 * MICROS truncation gives the timestamps).
 *
 * <h2>Identifier</h2>
 *
 * <p>BIGSERIAL surrogate via {@link GenerationType#IDENTITY} (same strategy as
 * {@code SettlementConfigEntity}): rows are minted fresh on every SCD-6 write
 * and nothing outside this package joins on their ids, so Spring Data routes
 * these through {@code em.persist()} and {@code @PrePersist} fires on the
 * entity itself.
 */
@Entity
@Table(name = "partner_prefunding_config")
public class PrefundingConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** FK to {@code partners.id} (the V003/V004 BIGINT surrogate). */
    @Column(name = "partner_id", nullable = false, updatable = false)
    private Long partnerId;

    /** Funding model (V015 CHECK roster: PREFUNDED / POSTPAID / HYBRID); never NULL. */
    @Column(name = "funding_model", nullable = false, length = 10)
    private String fundingModel;

    /** Initial float wired before go-live, major USD units; NULL until confirmed. */
    @Column(name = "opening_balance_usd", precision = 19, scale = 4)
    private BigDecimal openingBalanceUsd;

    /** Balance level arming the low-balance alert tiers; positive (V015 CHECK). */
    @Column(name = "low_balance_threshold_usd", nullable = false, precision = 19, scale = 4)
    private BigDecimal lowBalanceThresholdUsd;

    /** Tier-70 alert armed. */
    @Column(name = "alert_tier_70", nullable = false)
    private boolean alertTier70 = true;

    /** Tier-85 alert armed. */
    @Column(name = "alert_tier_85", nullable = false)
    private boolean alertTier85 = true;

    /** Tier-95 alert armed. */
    @Column(name = "alert_tier_95", nullable = false)
    private boolean alertTier95 = true;

    /** Credit line for POSTPAID/HYBRID; NULL = no limit configured. */
    @Column(name = "credit_limit_usd", precision = 19, scale = 4)
    private BigDecimal creditLimitUsd;

    /** Breach proposes a system change_request(status:SUSPENDED) when TRUE. */
    @Column(name = "auto_suspend_on_breach", nullable = false)
    private boolean autoSuspendOnBreach = true;

    /**
     * Loose reference to the {@code partner_bank_account} top-up row (V012,
     * purpose=FLOAT_TOPUP — validated by the service at write time), or NULL.
     */
    @Column(name = "float_top_up_bank_account_id")
    private Long floatTopUpBankAccountId;

    /** Wire-reference template; always contains {@code {partner_code}} (service-enforced). */
    @Column(name = "top_up_reference_pattern", length = 60)
    private String topUpReferencePattern;

    /** Optional collateral posted against a POSTPAID credit line. */
    @Column(name = "collateral_amount_usd", precision = 19, scale = 4)
    private BigDecimal collateralAmountUsd;

    /** Business-time lower bound (inclusive), ADR-010. */
    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    /** Business-time upper bound (exclusive); NULL = open-ended. */
    @Column(name = "valid_to")
    private Instant validTo;

    /** Transaction-time: when this row was recorded. Never NULL. */
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    /** Transaction-time: when this row stopped being current; NULL on current rows. */
    @Column(name = "superseded_at")
    private Instant supersededAt;

    public PrefundingConfigEntity() {
        // JPA
    }

    @jakarta.persistence.PrePersist
    void onPersist() {
        if (recordedAt == null) {
            // MICROS truncation: the stored TIMESTAMP must equal the in-memory
            // value on both PostgreSQL and H2 — same discipline as
            // PartnerEntity.onPersist (Slice 1 lesson).
            recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
        if (validFrom == null) {
            validFrom = recordedAt;
        }
    }

    /** Adapt this row to the canonical {@link PrefundingConfigView} wire DTO. */
    public PrefundingConfigView toView() {
        return new PrefundingConfigView(
                id,
                fundingModel,
                openingBalanceUsd,
                lowBalanceThresholdUsd,
                alertTier70,
                alertTier85,
                alertTier95,
                creditLimitUsd,
                autoSuspendOnBreach,
                floatTopUpBankAccountId,
                topUpReferencePattern,
                collateralAmountUsd,
                validFrom,
                validTo,
                recordedAt);
    }

    public Long getId() {
        return id;
    }

    public Long getPartnerId() {
        return partnerId;
    }

    public void setPartnerId(Long partnerId) {
        this.partnerId = partnerId;
    }

    public String getFundingModel() {
        return fundingModel;
    }

    public void setFundingModel(String fundingModel) {
        this.fundingModel = fundingModel;
    }

    public BigDecimal getOpeningBalanceUsd() {
        return openingBalanceUsd;
    }

    public void setOpeningBalanceUsd(BigDecimal openingBalanceUsd) {
        this.openingBalanceUsd = openingBalanceUsd;
    }

    public BigDecimal getLowBalanceThresholdUsd() {
        return lowBalanceThresholdUsd;
    }

    public void setLowBalanceThresholdUsd(BigDecimal lowBalanceThresholdUsd) {
        this.lowBalanceThresholdUsd = lowBalanceThresholdUsd;
    }

    public boolean isAlertTier70() {
        return alertTier70;
    }

    public void setAlertTier70(boolean alertTier70) {
        this.alertTier70 = alertTier70;
    }

    public boolean isAlertTier85() {
        return alertTier85;
    }

    public void setAlertTier85(boolean alertTier85) {
        this.alertTier85 = alertTier85;
    }

    public boolean isAlertTier95() {
        return alertTier95;
    }

    public void setAlertTier95(boolean alertTier95) {
        this.alertTier95 = alertTier95;
    }

    public BigDecimal getCreditLimitUsd() {
        return creditLimitUsd;
    }

    public void setCreditLimitUsd(BigDecimal creditLimitUsd) {
        this.creditLimitUsd = creditLimitUsd;
    }

    public boolean isAutoSuspendOnBreach() {
        return autoSuspendOnBreach;
    }

    public void setAutoSuspendOnBreach(boolean autoSuspendOnBreach) {
        this.autoSuspendOnBreach = autoSuspendOnBreach;
    }

    public Long getFloatTopUpBankAccountId() {
        return floatTopUpBankAccountId;
    }

    public void setFloatTopUpBankAccountId(Long floatTopUpBankAccountId) {
        this.floatTopUpBankAccountId = floatTopUpBankAccountId;
    }

    public String getTopUpReferencePattern() {
        return topUpReferencePattern;
    }

    public void setTopUpReferencePattern(String topUpReferencePattern) {
        this.topUpReferencePattern = topUpReferencePattern;
    }

    public BigDecimal getCollateralAmountUsd() {
        return collateralAmountUsd;
    }

    public void setCollateralAmountUsd(BigDecimal collateralAmountUsd) {
        this.collateralAmountUsd = collateralAmountUsd;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(Instant validFrom) {
        this.validFrom = validFrom;
    }

    public Instant getValidTo() {
        return validTo;
    }

    public void setValidTo(Instant validTo) {
        this.validTo = validTo;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(Instant recordedAt) {
        this.recordedAt = recordedAt;
    }

    public Instant getSupersededAt() {
        return supersededAt;
    }

    public void setSupersededAt(Instant supersededAt) {
        this.supersededAt = supersededAt;
    }
}
