package com.gme.pay.registry.scheme;

import com.gme.pay.contracts.PartnerSchemeView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * JPA-mapped row of the {@code partner_scheme} table (V022) — one scheme
 * enablement per (partner × scheme), bitemporally versioned per ADR-010
 * (Slice 7 — Scheme Enablement).
 *
 * <h2>Bitemporal storage</h2>
 *
 * <p>Same SCD-6 discipline as {@code PartnerEntity} (V004) and
 * {@code RuleEntity} (V017): rows are NEVER UPDATEd in place. A wizard step-7
 * save is a bulk replace — every current scheme row of the partner gets
 * {@code superseded_at = now} and the new set is INSERTed with
 * {@code recorded_at = now}, both halves sharing one MICROS-truncated instant
 * (see {@link PartnerSchemeService}).
 *
 * <p>The V022 column {@code current_scheme_key} is the cross-engine
 * partial-unique emulation enforcing one CURRENT row per (partner × scheme):
 * {@code 'partner_id:scheme_id'} on the current row, {@code NULL} on
 * superseded rows, with a plain UNIQUE index on it. It is
 * APPLICATION-maintained (a stored GENERATED column has no spelling both PG
 * and H2 parse — PG requires {@code STORED}, H2 rejects it — the V017 lesson):
 * {@link #onPersist} stamps it on INSERT and {@link #onUpdate} clears it when
 * the row is superseded, so the invariant holds no matter which write path
 * flushes the entity.
 *
 * <h2>Identifier</h2>
 *
 * <p>Engine-managed identity via {@link GenerationType#IDENTITY} (same
 * strategy as {@code RuleEntity}): rows are minted fresh on every SCD-6 write
 * and nothing outside this package joins on their ids.
 */
@Entity
@Table(name = "partner_scheme")
public class PartnerSchemeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** FK to {@code partners.id} (the V003/V004 BIGINT surrogate). */
    @Column(name = "partner_id", nullable = false, updatable = false)
    private Long partnerId;

    /** Scheme half of the enablement key (V022 CHECK roster). */
    @Column(name = "scheme_id", nullable = false, length = 20)
    private String schemeId;

    /** V022 CHECK roster: INBOUND / OUTBOUND / BOTH. */
    @Column(name = "direction", nullable = false, length = 10)
    private String direction;

    /** V022 CHECK roster: ACQUIRER / ISSUER / BOTH. */
    @Column(name = "role", nullable = false, length = 10)
    private String role;

    /** ZeroPay merchant id; nullable while the draft is incomplete. */
    @Column(name = "zeropay_merchant_id", length = 40)
    private String zeropayMerchantId;

    /** ZeroPay sub-merchant id; nullable. */
    @Column(name = "zeropay_sub_merchant_id", length = 40)
    private String zeropaySubMerchantId;

    /** KFTC institution code; nullable while the draft is incomplete. */
    @Column(name = "kftc_institution_code", length = 20)
    private String kftcInstitutionCode;

    /** Scheme-side partner classification: 'D' (direct) / 'I' (indirect); nullable. */
    @Column(name = "partner_type_char", length = 1)
    private String partnerTypeChar;

    /** Opaque ADR-006 vault locator for the scheme API credential; nullable. */
    @Column(name = "vault_secret_id", length = 64)
    private String vaultSecretId;

    /** Customer-presented-mode approval method: CONFIRMATION / SILENT; nullable. */
    @Column(name = "approval_method_cpm", length = 20)
    private String approvalMethodCpm;

    /** Merchant-presented-mode approval method: CONFIRMATION / SILENT; nullable. */
    @Column(name = "approval_method_mpm", length = 20)
    private String approvalMethodMpm;

    /** Kill switch (V022 DEFAULT true): a disabled row keeps its wiring but routes nothing. */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

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

    /**
     * Partial-unique emulation key (V022): {@code partner_id:scheme_id} while
     * this row is current, {@code NULL} once superseded. Maintained by the
     * lifecycle hooks below; the UNIQUE index {@code partner_scheme_current}
     * enforces "one current row per key" on both engines.
     */
    @Column(name = "current_scheme_key", length = 50)
    private String currentSchemeKey;

    /**
     * ADR-016 — the QR network GUID(s) this enablement can front, as a
     * COMMA-SEPARATED list (e.g. {@code com.zeropay};
     * {@code fonepay.com,nepalpay,khalti,mobank,unionpay,smartqr}). A scanned
     * QR is classified to one GUID and routed to the partner_scheme row(s)
     * whose list CONTAINS it; smart-router does the membership filter + ordering.
     * Nullable/additive (V037). Defaulted from {@code scheme_id} in
     * {@link #onPersist} for rows inserted through the frozen write command,
     * which carries no {@code networkIdentifier} field.
     */
    @Column(name = "network_identifier", length = 200)
    private String networkIdentifier;

    /**
     * Default {@code scheme_id -> network_identifier} map for the schemes that
     * front a fixed QR network set. MIRRORS the V037 back-fill UPDATEs so a row
     * inserted after the migration carries the same identifier the migration
     * would have stamped. Absent scheme_ids leave {@code networkIdentifier}
     * NULL (unmapped until an operator sets it). Package-visible so tests can
     * assert the seeded expectation against one source of truth.
     */
    static String defaultNetworkIdentifierFor(String schemeId) {
        if ("ZEROPAY".equals(schemeId)) {
            return "com.zeropay";
        }
        if ("NEPAL".equals(schemeId)) {
            return "fonepay.com,nepalpay,khalti,mobank,unionpay,smartqr";
        }
        return null;
    }

    public PartnerSchemeEntity() {
        // JPA
    }

    @jakarta.persistence.PrePersist
    void onPersist() {
        if (recordedAt == null) {
            // MICROS truncation: the stored TIMESTAMP must equal the in-memory
            // value on both PostgreSQL and H2 — same discipline as
            // PartnerEntity.onPersist / RuleEntity.onPersist (Slice 1 lesson).
            recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
        if (validFrom == null) {
            validFrom = recordedAt;
        }
        if (enabled == null) {
            // Mirrors the V022 column DEFAULT TRUE for entities built without one.
            enabled = Boolean.TRUE;
        }
        // Fresh rows are current by definition: stamp the partial-unique key
        // (superseded rows are never INSERTed — SCD-6 supersession is an UPDATE
        // of the prior row's superseded_at, handled by onUpdate below).
        if (supersededAt == null && currentSchemeKey == null) {
            currentSchemeKey = partnerId + ":" + schemeId;
        }
        // ADR-016: rows are INSERTed via a frozen write command with no
        // networkIdentifier field, so derive it from the scheme_id (same map as
        // the V037 back-fill) unless one was set explicitly. Null-safe: an
        // unmapped scheme stays NULL.
        if (networkIdentifier == null) {
            networkIdentifier = defaultNetworkIdentifierFor(schemeId);
        }
    }

    @jakarta.persistence.PreUpdate
    void onUpdate() {
        // The only sanctioned UPDATE under SCD-6 is closing the transaction-time
        // interval; the partial-unique key must vacate the index slot so the
        // replacing row can claim it within the same transaction.
        if (supersededAt != null) {
            currentSchemeKey = null;
        }
    }

    /**
     * Adapt this row to the canonical {@link PartnerSchemeView} wire DTO.
     * Carries the ADR-016 {@code networkIdentifier} (null-safe) so the
     * partner-schemes read surface exposes the QR-network map; the location
     * fields stay null on this projection ({@link #toLocationView} adds them).
     */
    public PartnerSchemeView toView() {
        return new PartnerSchemeView(
                partnerId,
                schemeId,
                direction,
                role,
                zeropayMerchantId,
                zeropaySubMerchantId,
                kftcInstitutionCode,
                partnerTypeChar,
                vaultSecretId,
                approvalMethodCpm,
                approvalMethodMpm,
                enabled,
                null,
                null,
                null,
                null,
                null,
                networkIdentifier);
    }

    /**
     * Adapt this row to the Wave-3 location-resolution {@link PartnerSchemeView}
     * — the canonical projection plus the five fields smart-router needs to pick
     * a scheme for a (country, presentment-mode, direction) tuple. Derived from
     * the columns this service already stores:
     * <ul>
     *   <li>{@code countryCode} — the owning partner's operating country, passed
     *       in by the service (this row carries no country of its own);</li>
     *   <li>{@code supportsCpm} / {@code supportsMpm} — whether the row declares
     *       an approval method for each presentment mode
     *       ({@code approval_method_cpm}/{@code _mpm} non-null);</li>
     *   <li>{@code priority} — {@code null}: there is no per-row selection-order
     *       column yet, so the resolver falls back to its own ordering;</li>
     *   <li>{@code status} — {@code ACTIVE} when the kill switch is on,
     *       {@code SUSPENDED} when off — orthogonal to {@code enabled} on the
     *       wire but derived from it until a dedicated lifecycle column exists.</li>
     *   <li>{@code networkIdentifier} — the ADR-016 QR-network GUID list this
     *       row fronts (null-safe); smart-router consumes it to map a scanned
     *       QR's network to candidate partners.</li>
     * </ul>
     */
    public PartnerSchemeView toLocationView(String countryCode) {
        boolean on = Boolean.TRUE.equals(enabled);
        return new PartnerSchemeView(
                partnerId,
                schemeId,
                direction,
                role,
                zeropayMerchantId,
                zeropaySubMerchantId,
                kftcInstitutionCode,
                partnerTypeChar,
                vaultSecretId,
                approvalMethodCpm,
                approvalMethodMpm,
                enabled,
                countryCode,
                approvalMethodCpm != null,
                approvalMethodMpm != null,
                null,
                on ? "ACTIVE" : "SUSPENDED",
                networkIdentifier);
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

    public String getSchemeId() {
        return schemeId;
    }

    public void setSchemeId(String schemeId) {
        this.schemeId = schemeId;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getZeropayMerchantId() {
        return zeropayMerchantId;
    }

    public void setZeropayMerchantId(String zeropayMerchantId) {
        this.zeropayMerchantId = zeropayMerchantId;
    }

    public String getZeropaySubMerchantId() {
        return zeropaySubMerchantId;
    }

    public void setZeropaySubMerchantId(String zeropaySubMerchantId) {
        this.zeropaySubMerchantId = zeropaySubMerchantId;
    }

    public String getKftcInstitutionCode() {
        return kftcInstitutionCode;
    }

    public void setKftcInstitutionCode(String kftcInstitutionCode) {
        this.kftcInstitutionCode = kftcInstitutionCode;
    }

    public String getPartnerTypeChar() {
        return partnerTypeChar;
    }

    public void setPartnerTypeChar(String partnerTypeChar) {
        this.partnerTypeChar = partnerTypeChar;
    }

    public String getVaultSecretId() {
        return vaultSecretId;
    }

    public void setVaultSecretId(String vaultSecretId) {
        this.vaultSecretId = vaultSecretId;
    }

    public String getApprovalMethodCpm() {
        return approvalMethodCpm;
    }

    public void setApprovalMethodCpm(String approvalMethodCpm) {
        this.approvalMethodCpm = approvalMethodCpm;
    }

    public String getApprovalMethodMpm() {
        return approvalMethodMpm;
    }

    public void setApprovalMethodMpm(String approvalMethodMpm) {
        this.approvalMethodMpm = approvalMethodMpm;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
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

    public String getCurrentSchemeKey() {
        return currentSchemeKey;
    }

    /** ADR-016 QR-network GUID list (comma-separated); nullable. */
    public String getNetworkIdentifier() {
        return networkIdentifier;
    }

    public void setNetworkIdentifier(String networkIdentifier) {
        this.networkIdentifier = networkIdentifier;
    }
}
