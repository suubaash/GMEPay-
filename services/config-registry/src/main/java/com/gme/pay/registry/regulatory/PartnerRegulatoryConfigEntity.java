package com.gme.pay.registry.regulatory;

import com.gme.pay.contracts.BokFxReportingCategory;
import com.gme.pay.contracts.BokRemitterType;
import com.gme.pay.contracts.LegalBasisCode;
import com.gme.pay.contracts.PartnerRegulatoryConfigView;
import com.gme.pay.contracts.TravelRuleProtocol;
import com.gme.pay.contracts.VatTreatment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * JPA-mapped row of the {@code partner_regulatory_config} table (V029) — the
 * partner's regulatory metadata (BOK 외환거래보고 + Hometax e-tax-invoice +
 * KoFIU CTR/STR + PIPA cross-border + Travel Rule), bitemporally versioned
 * per ADR-010 (Slice 8 Lane C — Regulatory attributes).
 *
 * <h2>Bitemporal storage</h2>
 *
 * <p>Same SCD-6 discipline as {@code RuleEntity} (V017) and
 * {@code PrefundingConfigEntity} (V015): rows are NEVER UPDATEd in place. A
 * wizard step-8 save is a full-state replace — the current row (if any) gets
 * {@code superseded_at = now} and a fresh row is INSERTed with
 * {@code recorded_at = now}, both halves sharing one MICROS-truncated instant
 * (see {@link PartnerRegulatoryConfigService}). On top of the V004 column
 * pairs this table carries the change-provenance pair {@code changed_by} /
 * {@code change_request_id} (NULL change-request for direct ONBOARDING
 * writes; the Slice 8 FSM threads real ids through post-activation).
 *
 * <p>The V029 column {@code current_partner_key} is the cross-engine
 * partial-unique emulation enforcing one CURRENT row per partner: the
 * {@code partner_id} on the current row, {@code NULL} on superseded rows,
 * with a plain UNIQUE index on it. It is APPLICATION-maintained (the V017
 * pattern — a stored GENERATED column has no spelling both PG and H2 parse):
 * {@link #onPersist} stamps it on INSERT and {@link #onUpdate} clears it when
 * the row is superseded, so the invariant holds no matter which write path
 * flushes the entity.
 *
 * <h2>Money</h2>
 *
 * <p>{@code ctrThresholdKrw} / {@code travelRuleThresholdKrw} are major-KRW
 * money, NUMERIC(18,2) per {@code docs/MONEY_CONVENTION.md} — BigDecimal
 * here, decimal STRING on the wire. The service normalises both to scale 2
 * before persisting so stored == in-memory on both engines.
 *
 * <h2>Identifier</h2>
 *
 * <p>{@code BIGINT GENERATED ALWAYS AS IDENTITY} surrogate via
 * {@link GenerationType#IDENTITY} (same strategy as
 * {@code PartnerCorridorEntity}): rows are minted fresh on every SCD-6 write
 * and nothing outside this package joins on their ids.
 */
@Entity
@Table(name = "partner_regulatory_config")
public class PartnerRegulatoryConfigEntity {

    /** Separator of the V029 {@code pipa_jurisdiction_allowlist} CSV column. */
    static final String CSV_SEPARATOR = ",";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** FK to {@code partners.id} (the V003/V004 BIGINT surrogate). */
    @Column(name = "partner_id", nullable = false, updatable = false)
    private Long partnerId;

    /** BOK external-trade code; service-enforced {@code ^\d{3}$} (TODO OI-03). */
    @Column(name = "bok_txn_code", length = 10)
    private String bokTxnCode;

    /** V029 CHECK roster: INDIVIDUAL_AGGREGATE / INSTITUTIONAL. */
    @Column(name = "bok_fx_reporting_category", length = 40)
    private String bokFxReportingCategory;

    /** V029 CHECK roster: INDIVIDUAL / CORPORATION / GOVERNMENT / FINANCIAL_INSTITUTION. */
    @Column(name = "bok_remitter_type", length = 40)
    private String bokRemitterType;

    /** lib-vault document id of the Hometax e-tax-invoice signing certificate. */
    @Column(name = "hometax_issuer_cert_id", length = 64)
    private String hometaxIssuerCertId;

    /** V029 CHECK roster: ZERO_RATED_EXPORT / STANDARD / EXEMPT. */
    @Column(name = "vat_treatment", length = 40)
    private String vatTreatment;

    /** KoFIU reporting-entity identifier for the CTR/STR feed. */
    @Column(name = "kofiu_entity_id", length = 40)
    private String kofiuEntityId;

    /** CTR threshold, major KRW; never NULL (statutory default 10,000,000). */
    @Column(name = "ctr_threshold_krw", nullable = false, precision = 18, scale = 2)
    private BigDecimal ctrThresholdKrw;

    /** CSV of ISO-3166 alpha-2 codes; the SERVICE parses + validates (V029 TEXT). */
    @Column(name = "pipa_jurisdiction_allowlist", columnDefinition = "text")
    private String pipaJurisdictionAllowlist;

    /** V029 CHECK roster: the PIPA six-basis taxonomy. */
    @Column(name = "legal_basis_code", length = 40)
    private String legalBasisCode;

    /** V029 CHECK roster: TRP / SYGNA / IVMS101 / NONE. */
    @Column(name = "travel_rule_protocol", length = 40)
    private String travelRuleProtocol;

    /** Counterparty VASP endpoint; REQUIRED when the protocol is present and != NONE. */
    @Column(name = "travel_rule_endpoint_url", length = 500)
    private String travelRuleEndpointUrl;

    /** Travel-Rule threshold, major KRW (statutory default 1,000,000). */
    @Column(name = "travel_rule_threshold_krw", precision = 18, scale = 2)
    private BigDecimal travelRuleThresholdKrw;

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

    /** Operator (X-Actor) who wrote this row version. */
    @Column(name = "changed_by", length = 120)
    private String changedBy;

    /** V005 change_request that authorised a post-activation change; NULL while ONBOARDING. */
    @Column(name = "change_request_id")
    private Long changeRequestId;

    /**
     * Partial-unique emulation key (V029): the {@code partner_id} while this
     * row is current, {@code NULL} once superseded. Maintained by the
     * lifecycle hooks below; the UNIQUE index
     * {@code partner_regulatory_config_current} enforces "one current row per
     * partner" on both engines.
     */
    @Column(name = "current_partner_key")
    private Long currentPartnerKey;

    public PartnerRegulatoryConfigEntity() {
        // JPA
    }

    @jakarta.persistence.PrePersist
    void onPersist() {
        if (recordedAt == null) {
            // MICROS truncation: the stored TIMESTAMP must equal the in-memory
            // value on both PostgreSQL and H2 — same discipline as
            // RuleEntity.onPersist (Slice 1 lesson).
            recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
        if (validFrom == null) {
            validFrom = recordedAt;
        }
        if (ctrThresholdKrw == null) {
            // Mirrors the V029 column DEFAULT 10,000,000 (statutory KoFIU CTR
            // threshold) for entities built without one.
            ctrThresholdKrw = new BigDecimal("10000000").setScale(2);
        }
        if (travelRuleThresholdKrw == null) {
            // Mirrors the V029 column DEFAULT 1,000,000 (Travel Rule floor).
            travelRuleThresholdKrw = new BigDecimal("1000000").setScale(2);
        }
        // Fresh rows are current by definition: stamp the partial-unique key
        // (superseded rows are never INSERTed — SCD-6 supersession is an UPDATE
        // of the prior row's superseded_at, handled by onUpdate below).
        if (supersededAt == null && currentPartnerKey == null) {
            currentPartnerKey = partnerId;
        }
    }

    @jakarta.persistence.PreUpdate
    void onUpdate() {
        // The only sanctioned UPDATE under SCD-6 is closing the transaction-time
        // interval; the partial-unique key must vacate the index slot so the
        // replacing row can claim it within the same transaction.
        if (supersededAt != null) {
            currentPartnerKey = null;
        }
    }

    /** Adapt this row to the canonical {@link PartnerRegulatoryConfigView} wire DTO. */
    public PartnerRegulatoryConfigView toView() {
        return new PartnerRegulatoryConfigView(
                partnerId,
                bokTxnCode,
                bokFxReportingCategory == null
                        ? null : BokFxReportingCategory.valueOf(bokFxReportingCategory),
                bokRemitterType == null
                        ? null : BokRemitterType.valueOf(bokRemitterType),
                hometaxIssuerCertId,
                vatTreatment == null ? null : VatTreatment.valueOf(vatTreatment),
                kofiuEntityId,
                ctrThresholdKrw,
                jurisdictionList(),
                legalBasisCode == null ? null : LegalBasisCode.valueOf(legalBasisCode),
                travelRuleProtocol == null
                        ? null : TravelRuleProtocol.valueOf(travelRuleProtocol),
                travelRuleEndpointUrl,
                travelRuleThresholdKrw);
    }

    /** The CSV column exploded to the wire's {@code List<String>} (empty when none). */
    public List<String> jurisdictionList() {
        if (pipaJurisdictionAllowlist == null || pipaJurisdictionAllowlist.isBlank()) {
            return List.of();
        }
        return List.of(pipaJurisdictionAllowlist.split(CSV_SEPARATOR));
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

    public String getBokTxnCode() {
        return bokTxnCode;
    }

    public void setBokTxnCode(String bokTxnCode) {
        this.bokTxnCode = bokTxnCode;
    }

    public String getBokFxReportingCategory() {
        return bokFxReportingCategory;
    }

    public void setBokFxReportingCategory(String bokFxReportingCategory) {
        this.bokFxReportingCategory = bokFxReportingCategory;
    }

    public String getBokRemitterType() {
        return bokRemitterType;
    }

    public void setBokRemitterType(String bokRemitterType) {
        this.bokRemitterType = bokRemitterType;
    }

    public String getHometaxIssuerCertId() {
        return hometaxIssuerCertId;
    }

    public void setHometaxIssuerCertId(String hometaxIssuerCertId) {
        this.hometaxIssuerCertId = hometaxIssuerCertId;
    }

    public String getVatTreatment() {
        return vatTreatment;
    }

    public void setVatTreatment(String vatTreatment) {
        this.vatTreatment = vatTreatment;
    }

    public String getKofiuEntityId() {
        return kofiuEntityId;
    }

    public void setKofiuEntityId(String kofiuEntityId) {
        this.kofiuEntityId = kofiuEntityId;
    }

    public BigDecimal getCtrThresholdKrw() {
        return ctrThresholdKrw;
    }

    public void setCtrThresholdKrw(BigDecimal ctrThresholdKrw) {
        this.ctrThresholdKrw = ctrThresholdKrw;
    }

    public String getPipaJurisdictionAllowlist() {
        return pipaJurisdictionAllowlist;
    }

    public void setPipaJurisdictionAllowlist(String pipaJurisdictionAllowlist) {
        this.pipaJurisdictionAllowlist = pipaJurisdictionAllowlist;
    }

    public String getLegalBasisCode() {
        return legalBasisCode;
    }

    public void setLegalBasisCode(String legalBasisCode) {
        this.legalBasisCode = legalBasisCode;
    }

    public String getTravelRuleProtocol() {
        return travelRuleProtocol;
    }

    public void setTravelRuleProtocol(String travelRuleProtocol) {
        this.travelRuleProtocol = travelRuleProtocol;
    }

    public String getTravelRuleEndpointUrl() {
        return travelRuleEndpointUrl;
    }

    public void setTravelRuleEndpointUrl(String travelRuleEndpointUrl) {
        this.travelRuleEndpointUrl = travelRuleEndpointUrl;
    }

    public BigDecimal getTravelRuleThresholdKrw() {
        return travelRuleThresholdKrw;
    }

    public void setTravelRuleThresholdKrw(BigDecimal travelRuleThresholdKrw) {
        this.travelRuleThresholdKrw = travelRuleThresholdKrw;
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

    public String getChangedBy() {
        return changedBy;
    }

    public void setChangedBy(String changedBy) {
        this.changedBy = changedBy;
    }

    public Long getChangeRequestId() {
        return changeRequestId;
    }

    public void setChangeRequestId(Long changeRequestId) {
        this.changeRequestId = changeRequestId;
    }
}
