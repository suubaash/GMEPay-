package com.gme.pay.kybadapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import com.gme.pay.kyb.ScreeningResult;
import com.gme.pay.kybadapter.kyb.BusinessRegistrationVerifier.BizRegStatus;
import com.gme.pay.kybadapter.kyb.KybDecision;

/**
 * The persisted adapter-side run log of one KYB verification (table
 * {@code kyb_screening}, V001). One row per completed run, keyed (uniquely) by
 * the deterministic {@code providerRef} so a re-screen of an unchanged subject
 * finds the existing row and replays it.
 *
 * <p>Only the hit COUNT is stored — the full hit detail rides the synchronous
 * response and the {@code gmepay.kyb.verification} event. This row is the
 * durable verdict + audit timestamp, not the evidence vault.
 */
@Entity
@Table(name = "kyb_screening")
public class KybScreeningRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_ref", nullable = false, unique = true, length = 96)
    private String providerRef;

    @Column(name = "partner_code", nullable = false, length = 64)
    private String partnerCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "screening_status", nullable = false, length = 16)
    private ScreeningResult.Status screeningStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "biz_reg_status", nullable = false, length = 16)
    private BizRegStatus bizRegStatus;

    @Column(name = "biz_reg_ref", length = 96)
    private String bizRegRef;

    @Column(name = "documents_complete", nullable = false)
    private boolean documentsComplete;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 16)
    private KybDecision decision;

    @Column(name = "decision_reason", length = 512)
    private String decisionReason;

    @Column(name = "hit_count", nullable = false)
    private int hitCount;

    @Column(name = "screened_at", nullable = false)
    private Instant screenedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected KybScreeningRecord() {
        // JPA
    }

    public KybScreeningRecord(String providerRef, String partnerCode,
            ScreeningResult.Status screeningStatus, BizRegStatus bizRegStatus, String bizRegRef,
            boolean documentsComplete, KybDecision decision, String decisionReason,
            int hitCount, Instant screenedAt, Instant createdAt) {
        this.providerRef = providerRef;
        this.partnerCode = partnerCode;
        this.screeningStatus = screeningStatus;
        this.bizRegStatus = bizRegStatus;
        this.bizRegRef = bizRegRef;
        this.documentsComplete = documentsComplete;
        this.decision = decision;
        this.decisionReason = decisionReason;
        this.hitCount = hitCount;
        this.screenedAt = screenedAt;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public String getPartnerCode() {
        return partnerCode;
    }

    public ScreeningResult.Status getScreeningStatus() {
        return screeningStatus;
    }

    public BizRegStatus getBizRegStatus() {
        return bizRegStatus;
    }

    public String getBizRegRef() {
        return bizRegRef;
    }

    public boolean isDocumentsComplete() {
        return documentsComplete;
    }

    public KybDecision getDecision() {
        return decision;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public int getHitCount() {
        return hitCount;
    }

    public Instant getScreenedAt() {
        return screenedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
