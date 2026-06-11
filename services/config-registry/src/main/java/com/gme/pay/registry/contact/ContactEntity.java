package com.gme.pay.registry.contact;

import com.gme.pay.contracts.ContactView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * JPA-mapped row of the {@code partner_contact} table (V009) — one contact of a
 * partner, bitemporally versioned per ADR-010 (Slice 2).
 *
 * <h2>Bitemporal storage</h2>
 *
 * <p>Same SCD-6 discipline as {@code PartnerEntity} (V004): rows are NEVER
 * UPDATEd in place. The wizard's step-2 save is a bulk replace — every current
 * row for the partner gets {@code superseded_at = now} and the new set is
 * INSERTed with {@code recorded_at = now}, both halves sharing one
 * MICROS-truncated instant (see {@link PartnerContactService#replaceDraftContacts}).
 *
 * <h2>Identifier</h2>
 *
 * <p>The surrogate {@code id} is the V009 BIGSERIAL, engine-managed via
 * {@link GenerationType#IDENTITY} — the same strategy {@code AuditLogEntity}
 * uses. Contacts are minted fresh on every bulk replace and nothing outside this
 * service joins on contact ids, so there is no need for the application-pulled
 * sequence pattern {@code PartnerStore} uses for partners (and consequently no
 * manually-assigned-id {@code em.merge()} pitfall: Spring Data routes these
 * through {@code em.persist()}, so {@code @PrePersist} fires on the entity
 * itself).
 */
@Entity
@Table(name = "partner_contact")
public class ContactEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** FK to {@code partners.id} (the V003/V004 BIGINT surrogate). */
    @Column(name = "partner_id", nullable = false, updatable = false)
    private Long partnerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private ContactRole role;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    /** E.164 phone ({@code ^\+[1-9]\d{1,14}$}) or NULL; format enforced by the service. */
    @Column(name = "phone_e164", length = 17)
    private String phoneE164;

    /** TRUE when this person may approve bank-account changes (Slice 4 2-signatory rule). */
    @Column(name = "is_authorized_signatory", nullable = false)
    private boolean authorizedSignatory;

    @Column(name = "notes", length = 500)
    private String notes;

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

    public ContactEntity() {
        // JPA
    }

    @jakarta.persistence.PrePersist
    void onPersist() {
        if (recordedAt == null) {
            // Defensive default matching the V009 column DEFAULT. Truncated to
            // MICROS: both PostgreSQL and H2 store TIMESTAMP at microsecond
            // precision and ROUND the JVM's nanosecond Instant — an un-truncated
            // value can round UP in the database, making the stored recorded_at
            // later than the in-memory copy and silently breaking bitemporal
            // `recorded_at <= :recordedAt` predicates and the ADR-007 audit hash
            // chain. Same discipline as PartnerEntity.onPersist (Slice 1 lesson).
            recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
        if (validFrom == null) {
            // Contacts default to "true from when we recorded them" — unlike the
            // partners aggregate (which defaults to EPOCH), a contact fact has no
            // meaningful existence before it was captured.
            validFrom = recordedAt;
        }
    }

    /** Adapt this row to the canonical {@link ContactView} wire DTO. */
    public ContactView toView() {
        return new ContactView(
                id,
                role == null ? null : role.name(),
                name,
                email,
                phoneE164,
                authorizedSignatory,
                notes,
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

    public ContactRole getRole() {
        return role;
    }

    public void setRole(ContactRole role) {
        this.role = role;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhoneE164() {
        return phoneE164;
    }

    public void setPhoneE164(String phoneE164) {
        this.phoneE164 = phoneE164;
    }

    public boolean isAuthorizedSignatory() {
        return authorizedSignatory;
    }

    public void setAuthorizedSignatory(boolean authorizedSignatory) {
        this.authorizedSignatory = authorizedSignatory;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
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
