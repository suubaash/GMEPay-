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
import com.gme.pay.kyb.ScreeningProvenance;
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
 *
 * <h2>Provenance (V002, gap T1-4)</h2>
 *
 * <p>Every row records WHICH provider produced the screening and whether that
 * provider is an authority, plus the caveat that must travel with a
 * non-authoritative verdict. Rows written by the stub carry
 * {@code screening_authoritative = false} and a status of
 * {@link ScreeningResult.Status#NOT_SCREENED_NO_PROVIDER} — the type system
 * upstream ({@code ScreeningResult}) makes a non-authoritative {@code CLEAR}
 * unconstructible, so this column cannot receive one.
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
    @Column(name = "screening_status", nullable = false, length = 32)
    private ScreeningResult.Status screeningStatus;

    /** V002: producing provider id — {@code stub} / {@code unknown} / a vendor id. */
    @Column(name = "screening_provider_id", length = 32)
    private String screeningProviderId;

    /** V002: TRUE only when a real screening provider consulted screening sources. */
    @Column(name = "screening_authoritative", nullable = false)
    private boolean screeningAuthoritative;

    /** V002: why the run is not authoritative; NULL on an authoritative run. */
    @Column(name = "screening_caveat", length = 512)
    private String screeningCaveat;

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
            ScreeningResult.Status screeningStatus, ScreeningProvenance provenance,
            BizRegStatus bizRegStatus, String bizRegRef,
            boolean documentsComplete, KybDecision decision, String decisionReason,
            int hitCount, Instant screenedAt, Instant createdAt) {
        this.providerRef = providerRef;
        this.partnerCode = partnerCode;
        this.screeningStatus = screeningStatus;
        // Provenance is never optional on a persisted run: an absent one is
        // recorded as the explicitly non-authoritative "unknown" producer rather
        // than as a blank that a reader could mistake for a vendor result.
        ScreeningProvenance p = provenance == null ? ScreeningProvenance.unknown() : provenance;
        this.screeningProviderId = p.providerId();
        this.screeningAuthoritative = p.authoritative();
        this.screeningCaveat = p.caveat();
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

    public String getScreeningProviderId() {
        return screeningProviderId;
    }

    public boolean isScreeningAuthoritative() {
        return screeningAuthoritative;
    }

    public String getScreeningCaveat() {
        return screeningCaveat;
    }

    /**
     * The persisted provenance, rebuilt for the response path. A row whose
     * provenance predates V002 reads back as {@link ScreeningProvenance#unknown()}
     * — non-authoritative, never silently promoted.
     */
    public ScreeningProvenance provenance() {
        if (screeningProviderId == null || screeningProviderId.isBlank()) {
            return ScreeningProvenance.unknown();
        }
        return screeningAuthoritative
                ? ScreeningProvenance.vendor(screeningProviderId)
                : new ScreeningProvenance(screeningProviderId,
                        false,
                        screeningCaveat == null || screeningCaveat.isBlank()
                                ? ScreeningProvenance.UNKNOWN_CAVEAT : screeningCaveat);
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
