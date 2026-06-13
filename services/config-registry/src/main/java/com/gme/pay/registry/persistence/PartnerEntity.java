package com.gme.pay.registry.persistence;

import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
// Slice 1 1C.2: explicit status enum column on the entity (see V008 migration).
// Kept as a regular field rather than as a derived value so the JPA round-trip
// is symmetric for both the seed rows (V001/V002 — default to ONBOARDING) and
// rows minted through the draft endpoints (POST /v1/partners/draft).
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * JPA-mapped partner row. Separate class from {@link Partner} because Hibernate
 * cannot manage Java records. {@code PartnerStore} converts between this entity
 * and the domain record at the persistence boundary.
 *
 * <h2>Bitemporal storage (V004 / ADR-010)</h2>
 *
 * <p>Each row carries two pairs of timestamps:
 * <ul>
 *   <li>{@code valid_from} / {@code valid_to} — <b>business time</b>: the half-open
 *       interval during which this row's fact was/is true. {@code valid_to} = NULL
 *       means open-ended.</li>
 *   <li>{@code recorded_at} / {@code superseded_at} — <b>transaction time</b>: when
 *       this row was written (never NULL), and when (if ever) it stopped being the
 *       current view ({@code superseded_at = NULL} on the row that is still current).</li>
 * </ul>
 *
 * <p>Storage discipline: rows are NEVER {@code UPDATE}d. Every change is a paired
 * (UPDATE prior_row SET superseded_at = now()) + (INSERT new_row) inside one
 * transaction. The application-layer enforcer is {@code PartnerStore.save}; the
 * database enforces "one current row per partner_code" via the partial unique
 * index {@code partners_current ON partners(partner_code) WHERE superseded_at
 * IS NULL} (V004).
 *
 * <h2>Identifier columns (V003 + V004)</h2>
 *
 * <p>Three columns identify a row, each with a distinct role:
 * <ul>
 *   <li>{@code id BIGINT PRIMARY KEY} (V003 + promoted to PK by V004): the surrogate,
 *       the universal join key. Every consuming service (auth-identity / notification-
 *       webhook / settlement-reconciliation) holds this. Filled at INSERT time from
 *       {@code partners_id_seq} via {@code PartnerStore.save}.</li>
 *   <li>{@code partner_code VARCHAR(20)}: the human-facing business key (e.g.
 *       {@code "GMEREMIT"}). Multiple rows may share a code (one current + N
 *       historicals); the partial unique index {@code partners_current} keeps at
 *       most one current row per code.</li>
 *   <li>{@code partner_id VARCHAR(32)}: the legacy column held over from V001
 *       through the Expand phase (ADR-013). Still populated (mirrors partner_code)
 *       so any caller on the old shape continues to work; no longer the PK and no
 *       longer carries a uniqueness constraint. A future Contract migration drops
 *       it.</li>
 * </ul>
 */
@Entity
@Table(name = "partners")
public class PartnerEntity {

    /**
     * Surrogate primary key (V003, promoted to PK by V004 — see the V004 migration
     * header). Filled at the application layer by {@code PartnerStore.save} which
     * pulls the next value from {@code partners_id_seq} before issuing the INSERT.
     * This avoids relying on Hibernate-specific {@code @GeneratedValue} support that
     * the H2 PostgreSQL-mode test database does not consistently honour.
     *
     * <p>{@code updatable = false} prevents subsequent UPDATEs from touching the
     * column — under SCD-6 we never UPDATE a row's content anyway, but the entity
     * field guards against accidental misuse from outside {@code PartnerStore}.
     */
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /**
     * Legacy VARCHAR identifier carried over from V001. During the Expand phase
     * (ADR-013) this mirrors {@link #partnerCode} on every row; it is no longer the
     * PK (V004) and no longer carries a uniqueness constraint, so multiple SCD-6
     * historical rows for the same business code all share the same legacy string
     * here. Kept on the entity so existing read paths that reach for the legacy
     * field continue to compile; a future Contract migration drops the column.
     */
    @Column(name = "partner_id", length = 32)
    private String partnerId;

    /** Human-facing business code (V003). Multiple rows may share a value under SCD-6. */
    @Column(name = "partner_code", length = 20)
    private String partnerCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private PartnerType type;

    @Column(name = "settlement_currency", length = 3)
    private String settlementCurrency;

    // ------------------------------------------------------------------------
    // Slice 6 currency split (V016 / ADR-013 Expand phase).
    //
    // settlement_currency conflates "what the partner collects in" with "what
    // GME settles to the partner in"; V016 splits it into collection_ccy +
    // settle_a_ccy. Both are nullable during the Expand phase (drafts may not
    // have chosen currencies yet); settlement_currency KEEPS being populated
    // until the Contract release drops it. onPersist() mirrors the legacy
    // value into either side that is still null, so pre-split write paths
    // (PartnerStore.save / step-1) produce rows indistinguishable from the
    // V016 backfill.
    // ------------------------------------------------------------------------

    /** ISO-4217 currency the partner COLLECTS from its senders in (V016). */
    @Column(name = "collection_ccy", length = 3, columnDefinition = "CHAR(3)")
    private String collectionCcy;

    /** ISO-4217 currency GME SETTLES with the partner in (V016). */
    @Column(name = "settle_a_ccy", length = 3, columnDefinition = "CHAR(3)")
    private String settleACcy;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_rounding_mode", nullable = false, length = 16)
    private RoundingMode settlementRoundingMode;

    /**
     * Business-time lower bound (inclusive). Renamed from {@code effective_from} by
     * V004 — the semantics are unchanged, the name aligns with ADR-010 terminology.
     */
    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    /**
     * Business-time upper bound (exclusive). {@code NULL} = open-ended. Renamed from
     * {@code effective_to} by V004.
     */
    @Column(name = "valid_to")
    private Instant validTo;

    /**
     * Transaction-time: when this row was recorded. Never NULL — defaulted by the
     * V004 column DDL to {@code now()} so the application does not have to set it,
     * but {@link #onPersist} also fills it defensively to make in-test entity
     * construction explicit.
     */
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    /**
     * Transaction-time: when this row stopped being current. {@code NULL} on the
     * current row by definition; set to a paired {@code now()} by the prior-row
     * UPDATE issued in {@code PartnerStore.save}. The partial unique index
     * {@code partners_current} relies on this to enforce "one current row per
     * partner_code".
     */
    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // ------------------------------------------------------------------------
    // Slice 1 Identity-step columns (V007 / ADR-013 Expand phase).
    //
    // Every field below is nullable on both the entity and the DB column so the
    // existing seed rows (GMEREMIT / SENDMN — created before Step-1 wizard
    // existed) keep working untouched and the Admin UI wizard can save partial
    // progress. Validation of each field's format is in PartnerValidator; the
    // entity is intentionally schema-shaped (storage concern), not policy-shaped.
    // ------------------------------------------------------------------------

    /** Legal name in the local script (e.g. Korean / Vietnamese / Khmer characters). */
    @Column(name = "legal_name_local", length = 200)
    private String legalNameLocal;

    /** Latin-alphabet Romanization of {@link #legalNameLocal} used in regulatory filings. */
    @Column(name = "legal_name_romanized", length = 200)
    private String legalNameRomanized;

    /**
     * Tax identifier whose format is selected by {@link #taxIdType}. Stored as the
     * raw operator-entered string; {@code PartnerValidator} enforces the regex per
     * discriminator before any write reaches this column.
     */
    @Column(name = "tax_id", length = 40)
    private String taxId;

    /**
     * Tax-id format discriminator: {@code KR_BRN} | {@code KH_VAT} | {@code VN_MST}
     * | {@code SG_UEN} | {@code GENERIC}. Stored as VARCHAR (not a JPA enum) so
     * adding new jurisdictions later does not churn this entity.
     */
    @Column(name = "tax_id_type", length = 20)
    private String taxIdType;

    /** ISO-3166 alpha-2 country code of the legal-entity incorporation jurisdiction. */
    @Column(name = "country_of_incorporation", length = 2, columnDefinition = "CHAR(2)")
    private String countryOfIncorporation;

    /** Legal form: {@code CORP} | {@code LLC} | {@code MTO} | {@code EMI} | {@code BANK} | {@code OTHER}. */
    @Column(name = "legal_form", length = 20)
    private String legalForm;

    /** Registered address — street line 1. */
    @Column(name = "registered_street1", length = 200)
    private String registeredStreet1;

    /** Registered address — street line 2 (often NULL). */
    @Column(name = "registered_street2", length = 200)
    private String registeredStreet2;

    @Column(name = "registered_city", length = 100)
    private String registeredCity;

    @Column(name = "registered_state", length = 100)
    private String registeredState;

    @Column(name = "registered_postcode", length = 20)
    private String registeredPostcode;

    /** ISO-3166 alpha-2 country code of the registered address. */
    @Column(name = "registered_country", length = 2, columnDefinition = "CHAR(2)")
    private String registeredCountry;

    /** Operating address — street line 1. */
    @Column(name = "operating_street1", length = 200)
    private String operatingStreet1;

    /** Operating address — street line 2 (often NULL). */
    @Column(name = "operating_street2", length = 200)
    private String operatingStreet2;

    @Column(name = "operating_city", length = 100)
    private String operatingCity;

    @Column(name = "operating_state", length = 100)
    private String operatingState;

    @Column(name = "operating_postcode", length = 20)
    private String operatingPostcode;

    /** ISO-3166 alpha-2 country code of the operating address. */
    @Column(name = "operating_country", length = 2, columnDefinition = "CHAR(2)")
    private String operatingCountry;

    /**
     * ISO 17442 Legal Entity Identifier — exactly 20 alphanumeric characters with
     * a mod-97-10 checksum in the trailing two positions. Optional; many partners
     * will not have one. {@code PartnerValidator.checkLei} enforces both the
     * length/charset and the checksum before a value lands here.
     */
    @Column(name = "lei", length = 20, columnDefinition = "CHAR(20)")
    private String lei;

    /**
     * Lifecycle status (Slice 1 1C.2 — V008). Defaults to {@link PartnerStatus#ONBOARDING}
     * so a freshly-built entity is always in a valid status; the DB column has the
     * same DEFAULT so the V001/V002 seed rows backfill atomically on Flyway-up. The
     * Slice 8 FSM walks rows forward through KYB_PENDING → … → LIVE; Slice 1 only
     * writes the ONBOARDING value.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PartnerStatus status = PartnerStatus.ONBOARDING;

    // ------------------------------------------------------------------------
    // Slice 8 lifecycle columns (V025 / ADR-011 Expand phase).
    //
    // All nullable: stamped by the 4-eyes lifecycle transitions and NULL on
    // every row that has not reached the corresponding state. go_live_at is
    // also the post-activation immutability lock marker (non-NULL = the
    // identity-critical columns are frozen — see PartnerImmutabilityGuard).
    // PartnerStore.save carries these forward onto each fresh SCD-6 row so a
    // settlement-attribute write can never silently "un-activate" a partner.
    // ------------------------------------------------------------------------

    /** Instant of the FIRST {@code UAT → LIVE} transition; never reset thereafter. */
    @Column(name = "go_live_at")
    private Instant goLiveAt;

    /** Operator (the 4-eyes checker) who completed the first activation. */
    @Column(name = "activated_by", length = 100)
    private String activatedBy;

    /** {@code SuspensionReason} name while SUSPENDED (V025 CHECK roster); else NULL. */
    @Column(name = "suspension_reason", length = 40)
    private String suspensionReason;

    /** Operator free text accompanying a suspension; cleared on reactivation. */
    @Column(name = "suspension_notes", length = 500)
    private String suspensionNotes;

    /** When the current suspension was applied; cleared on reactivation. */
    @Column(name = "suspended_at")
    private Instant suspendedAt;

    /** When the partner was terminated (terminal state — never cleared). */
    @Column(name = "terminated_at")
    private Instant terminatedAt;

    /** Operator free text for the termination decision. */
    @Column(name = "termination_reason", length = 500)
    private String terminationReason;

    public PartnerEntity() {
        // JPA
    }

    public PartnerEntity(String partnerCode, PartnerType type, String settlementCurrency,
                         RoundingMode settlementRoundingMode) {
        this.partnerCode = partnerCode;
        this.partnerId = partnerCode; // Expand phase: legacy column mirrors the business code.
        this.type = type;
        this.settlementCurrency = settlementCurrency;
        this.settlementRoundingMode = settlementRoundingMode;
    }

    /**
     * Build an entity from the domain record. Mirrors {@code partner_code} into
     * the legacy {@code partner_id} column as required by the Expand-phase contract
     * (V003 + V004).
     */
    public static PartnerEntity fromDomain(Partner partner) {
        return new PartnerEntity(
                partner.partnerCode(),
                partner.type(),
                partner.settlementCurrency(),
                partner.settlementRoundingMode());
    }

    /**
     * Convert this entity to the domain record. The surrogate {@code id} (which may
     * be {@code null} on a freshly-built entity that has not yet been flushed) is
     * carried through; the legacy {@code partnerId} column is folded into the same
     * {@code partnerCode} value during the Expand phase.
     */
    public Partner toDomain() {
        return new Partner(id, partnerCode != null ? partnerCode : partnerId,
                type, settlementCurrency, settlementRoundingMode);
    }

    @jakarta.persistence.PrePersist
    void onPersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (recordedAt == null) {
            // Defensive default to match the V004 column DEFAULT now(). Kept so a
            // test that builds an entity with `new PartnerEntity(...)` and saves it
            // without going through PartnerStore still gets a populated recorded_at.
            // Truncated to MICROS: both PostgreSQL and H2 store TIMESTAMP at
            // microsecond precision and ROUND the JVM's nanosecond Instant — an
            // un-truncated value can round UP in the database, making the stored
            // recorded_at later than the in-memory copy and silently breaking
            // bitemporal `recorded_at <= :recordedAt` predicates and the ADR-007
            // audit hash chain (hash computed over in-memory value ≠ stored row).
            recordedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        }
        if (validFrom == null) {
            validFrom = Instant.EPOCH; // effective "since forever" unless windowed explicitly
        }
        if (partnerCode == null && partnerId != null) {
            // Defensive: Expand phase guarantees the two stay aligned; mirror the
            // legacy value if a caller built via the no-arg constructor + setPartnerId.
            partnerCode = partnerId;
        }
        if (partnerId == null && partnerCode != null) {
            // And the reverse direction: keep the legacy column populated.
            partnerId = partnerCode;
        }
        // V016 Expand-phase mirror (Slice 6): until the commercial-terms step
        // writes a real collection/settle split, both sides default to the
        // legacy settlement_currency — the same fact the V016 backfill stamped
        // onto pre-split rows. Defensive: only fills sides that are null, so a
        // caller that already set a genuine split is never overwritten.
        if (collectionCcy == null && settlementCurrency != null) {
            collectionCcy = settlementCurrency;
        }
        if (settleACcy == null && settlementCurrency != null) {
            settleACcy = settlementCurrency;
        }
    }

    @jakarta.persistence.PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Surrogate primary key (BIGINT, V003/V004). */
    public Long getId() {
        return id;
    }

    /**
     * Setter for the surrogate (V003). Used by {@code PartnerStore.save} to fill
     * the value pulled from {@code partners_id_seq} before the first persist.
     */
    public void setId(Long id) {
        this.id = id;
    }

    public String getPartnerId() {
        return partnerId;
    }

    public void setPartnerId(String partnerId) {
        this.partnerId = partnerId;
    }

    public String getPartnerCode() {
        return partnerCode;
    }

    public void setPartnerCode(String partnerCode) {
        this.partnerCode = partnerCode;
    }

    public PartnerType getType() {
        return type;
    }

    public void setType(PartnerType type) {
        this.type = type;
    }

    public String getSettlementCurrency() {
        return settlementCurrency;
    }

    public void setSettlementCurrency(String settlementCurrency) {
        this.settlementCurrency = settlementCurrency;
    }

    /** ISO-4217 currency the partner collects from its senders in (V016 split). */
    public String getCollectionCcy() {
        return collectionCcy;
    }

    public void setCollectionCcy(String collectionCcy) {
        this.collectionCcy = collectionCcy;
    }

    /** ISO-4217 currency GME settles with the partner in (V016 split). */
    public String getSettleACcy() {
        return settleACcy;
    }

    public void setSettleACcy(String settleACcy) {
        this.settleACcy = settleACcy;
    }

    public RoundingMode getSettlementRoundingMode() {
        return settlementRoundingMode;
    }

    public void setSettlementRoundingMode(RoundingMode settlementRoundingMode) {
        this.settlementRoundingMode = settlementRoundingMode;
    }

    /** Business-time lower bound (inclusive); V004 rename of {@code effective_from}. */
    public Instant getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(Instant validFrom) {
        this.validFrom = validFrom;
    }

    /** Business-time upper bound (exclusive; NULL = open-ended); V004 rename of {@code effective_to}. */
    public Instant getValidTo() {
        return validTo;
    }

    public void setValidTo(Instant validTo) {
        this.validTo = validTo;
    }

    /**
     * Transaction-time: when this row was recorded. Set by {@link #onPersist}
     * (or by {@code PartnerStore.save} explicitly to keep paired-row transaction-time
     * boundaries in lock-step).
     */
    public Instant getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(Instant recordedAt) {
        this.recordedAt = recordedAt;
    }

    /** Transaction-time: when this row stopped being current; NULL on the current row. */
    public Instant getSupersededAt() {
        return supersededAt;
    }

    public void setSupersededAt(Instant supersededAt) {
        this.supersededAt = supersededAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    // ------------------------------------------------------------------------
    // Slice 1 Identity-step accessors (V007). Plain getter/setter pairs; no
    // validation in the entity — see PartnerValidator for the format rules.
    // ------------------------------------------------------------------------

    public String getLegalNameLocal() {
        return legalNameLocal;
    }

    public void setLegalNameLocal(String legalNameLocal) {
        this.legalNameLocal = legalNameLocal;
    }

    public String getLegalNameRomanized() {
        return legalNameRomanized;
    }

    public void setLegalNameRomanized(String legalNameRomanized) {
        this.legalNameRomanized = legalNameRomanized;
    }

    public String getTaxId() {
        return taxId;
    }

    public void setTaxId(String taxId) {
        this.taxId = taxId;
    }

    public String getTaxIdType() {
        return taxIdType;
    }

    public void setTaxIdType(String taxIdType) {
        this.taxIdType = taxIdType;
    }

    public String getCountryOfIncorporation() {
        return countryOfIncorporation;
    }

    public void setCountryOfIncorporation(String countryOfIncorporation) {
        this.countryOfIncorporation = countryOfIncorporation;
    }

    public String getLegalForm() {
        return legalForm;
    }

    public void setLegalForm(String legalForm) {
        this.legalForm = legalForm;
    }

    public String getRegisteredStreet1() {
        return registeredStreet1;
    }

    public void setRegisteredStreet1(String registeredStreet1) {
        this.registeredStreet1 = registeredStreet1;
    }

    public String getRegisteredStreet2() {
        return registeredStreet2;
    }

    public void setRegisteredStreet2(String registeredStreet2) {
        this.registeredStreet2 = registeredStreet2;
    }

    public String getRegisteredCity() {
        return registeredCity;
    }

    public void setRegisteredCity(String registeredCity) {
        this.registeredCity = registeredCity;
    }

    public String getRegisteredState() {
        return registeredState;
    }

    public void setRegisteredState(String registeredState) {
        this.registeredState = registeredState;
    }

    public String getRegisteredPostcode() {
        return registeredPostcode;
    }

    public void setRegisteredPostcode(String registeredPostcode) {
        this.registeredPostcode = registeredPostcode;
    }

    public String getRegisteredCountry() {
        return registeredCountry;
    }

    public void setRegisteredCountry(String registeredCountry) {
        this.registeredCountry = registeredCountry;
    }

    public String getOperatingStreet1() {
        return operatingStreet1;
    }

    public void setOperatingStreet1(String operatingStreet1) {
        this.operatingStreet1 = operatingStreet1;
    }

    public String getOperatingStreet2() {
        return operatingStreet2;
    }

    public void setOperatingStreet2(String operatingStreet2) {
        this.operatingStreet2 = operatingStreet2;
    }

    public String getOperatingCity() {
        return operatingCity;
    }

    public void setOperatingCity(String operatingCity) {
        this.operatingCity = operatingCity;
    }

    public String getOperatingState() {
        return operatingState;
    }

    public void setOperatingState(String operatingState) {
        this.operatingState = operatingState;
    }

    public String getOperatingPostcode() {
        return operatingPostcode;
    }

    public void setOperatingPostcode(String operatingPostcode) {
        this.operatingPostcode = operatingPostcode;
    }

    public String getOperatingCountry() {
        return operatingCountry;
    }

    public void setOperatingCountry(String operatingCountry) {
        this.operatingCountry = operatingCountry;
    }

    public String getLei() {
        return lei;
    }

    public void setLei(String lei) {
        this.lei = lei;
    }

    /** Lifecycle status (Slice 1 1C.2; column added in V008). */
    public PartnerStatus getStatus() {
        return status;
    }

    public void setStatus(PartnerStatus status) {
        this.status = status;
    }

    // ------------------------------------------------------------------------
    // Slice 8 lifecycle accessors (V025). Plain getter/setter pairs; the FSM
    // rules live in PartnerStatusTransitionTable + PartnerLifecycleService.
    // ------------------------------------------------------------------------

    /** First-go-live instant; non-NULL also means "immutability lock engaged". */
    public Instant getGoLiveAt() {
        return goLiveAt;
    }

    public void setGoLiveAt(Instant goLiveAt) {
        this.goLiveAt = goLiveAt;
    }

    public String getActivatedBy() {
        return activatedBy;
    }

    public void setActivatedBy(String activatedBy) {
        this.activatedBy = activatedBy;
    }

    public String getSuspensionReason() {
        return suspensionReason;
    }

    public void setSuspensionReason(String suspensionReason) {
        this.suspensionReason = suspensionReason;
    }

    public String getSuspensionNotes() {
        return suspensionNotes;
    }

    public void setSuspensionNotes(String suspensionNotes) {
        this.suspensionNotes = suspensionNotes;
    }

    public Instant getSuspendedAt() {
        return suspendedAt;
    }

    public void setSuspendedAt(Instant suspendedAt) {
        this.suspendedAt = suspendedAt;
    }

    public Instant getTerminatedAt() {
        return terminatedAt;
    }

    public void setTerminatedAt(Instant terminatedAt) {
        this.terminatedAt = terminatedAt;
    }

    public String getTerminationReason() {
        return terminationReason;
    }

    public void setTerminationReason(String terminationReason) {
        this.terminationReason = terminationReason;
    }
}
