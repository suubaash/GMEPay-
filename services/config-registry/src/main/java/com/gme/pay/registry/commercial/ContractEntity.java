package com.gme.pay.registry.commercial;

import com.gme.pay.contracts.ContractView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * JPA-mapped row of the {@code partner_contract} table (V021) — the
 * commercial contract terms of a partner, bitemporally versioned per ADR-010
 * (Slice 6 — Commercial Terms).
 *
 * <p>Mind the two date axes (see the V021 header): {@code effectiveFrom} /
 * {@code effectiveTo} is the commercial contract TERM (a business field,
 * {@link LocalDate}); {@code validFrom} / {@code validTo} is the ADR-010
 * business-time axis of the ROW VERSION. Same SCD-6 paired-write discipline
 * as {@code PrefundingConfigEntity} (V015) — see {@link ContractService}.
 */
@Entity
@Table(name = "partner_contract")
public class ContractEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** FK to {@code partners.id} (the V003/V004 BIGINT surrogate). */
    @Column(name = "partner_id", nullable = false, updatable = false)
    private Long partnerId;

    /** Contract term start (signed paper carries dates); never NULL. */
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Contract term end; NULL = open-ended / evergreen. */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    /** Evergreen auto-renewal at effective_to; never NULL (DB DEFAULT FALSE). */
    @Column(name = "auto_renewal", nullable = false)
    private boolean autoRenewal;

    /** Days of notice required to break auto-renewal; NULL = not agreed. */
    @Column(name = "notice_period_days")
    private Integer noticePeriodDays;

    /** Refund/chargeback bearer (V021 CHECK roster); NULL = not agreed yet. */
    @Column(name = "refund_chargeback_policy", length = 20)
    private String refundChargebackPolicy;

    /** Why terminated (Slice 8 lifecycle flow); NULL on live contracts. */
    @Column(name = "termination_reason", length = 200)
    private String terminationReason;

    /**
     * When the paper contract was countersigned (Slice 8 / V025). NULL until
     * the wizard's step-6 save stamps it; the activation gate requires it
     * non-NULL before {@code UAT → LIVE} (CONTRACT_NOT_SIGNED otherwise).
     * Deliberately NOT surfaced on {@link ContractView} yet — widening that
     * record's positional shape would ripple into the BFF stub, which Lane A
     * does not own (see Slice 8 lane split).
     */
    @Column(name = "signed_at")
    private Instant signedAt;

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

    public ContractEntity() {
        // JPA
    }

    @jakarta.persistence.PrePersist
    void onPersist() {
        if (recordedAt == null) {
            recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
        if (validFrom == null) {
            validFrom = recordedAt;
        }
    }

    /** Adapt this row to the canonical {@link ContractView} wire DTO. */
    public ContractView toView() {
        return new ContractView(
                id,
                effectiveFrom,
                effectiveTo,
                autoRenewal,
                noticePeriodDays,
                refundChargebackPolicy,
                terminationReason,
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

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(LocalDate effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public boolean isAutoRenewal() {
        return autoRenewal;
    }

    public void setAutoRenewal(boolean autoRenewal) {
        this.autoRenewal = autoRenewal;
    }

    public Integer getNoticePeriodDays() {
        return noticePeriodDays;
    }

    public void setNoticePeriodDays(Integer noticePeriodDays) {
        this.noticePeriodDays = noticePeriodDays;
    }

    public String getRefundChargebackPolicy() {
        return refundChargebackPolicy;
    }

    public void setRefundChargebackPolicy(String refundChargebackPolicy) {
        this.refundChargebackPolicy = refundChargebackPolicy;
    }

    public String getTerminationReason() {
        return terminationReason;
    }

    public void setTerminationReason(String terminationReason) {
        this.terminationReason = terminationReason;
    }

    /** Contract countersign instant (V025); NULL = not yet signed. */
    public Instant getSignedAt() {
        return signedAt;
    }

    public void setSignedAt(Instant signedAt) {
        this.signedAt = signedAt;
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
