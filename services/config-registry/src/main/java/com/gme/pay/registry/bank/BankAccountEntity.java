package com.gme.pay.registry.bank;

import com.gme.pay.contracts.BankAccountView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * JPA-mapped row of the {@code partner_bank_account} table (V012) — one bank
 * account of a partner, bitemporally versioned per ADR-010 (Slice 4).
 *
 * <h2>Bitemporal storage</h2>
 *
 * <p>Same SCD-6 discipline as {@code ContactEntity} (V009): rows are NEVER
 * UPDATEd in place. The wizard's step-4 save is a bulk replace — every current
 * row for the partner gets {@code superseded_at = now} and the new set is
 * INSERTed with {@code recorded_at = now}, both halves sharing one
 * MICROS-truncated instant (see
 * {@link PartnerBankAccountService#replaceDraftBankAccounts}). A verification
 * verdict is the same paired write on the single affected row
 * ({@link PartnerBankAccountService#verifyBankAccount}).
 *
 * <h2>Identifier</h2>
 *
 * <p>The surrogate {@code id} is the V012 BIGSERIAL, engine-managed via
 * {@link GenerationType#IDENTITY} — same strategy and same rationale as
 * {@code ContactEntity}: rows are minted fresh on every replace, Spring Data
 * routes them through {@code em.persist()}, and {@code @PrePersist} fires on
 * the entity itself (no manually-assigned-id {@code em.merge()} pitfall).
 */
@Entity
@Table(name = "partner_bank_account")
public class BankAccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** FK to {@code partners.id} (the V003/V004 BIGINT surrogate). */
    @Column(name = "partner_id", nullable = false, updatable = false)
    private Long partnerId;

    /** ISO-4217 currency ({@code ^[A-Z]{3}$}); shape enforced by the service. */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "bank_name", nullable = false, length = 140)
    private String bankName;

    /** BIC-8/BIC-11 ({@code ^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$}) or NULL. */
    @Column(name = "bic_swift", length = 11)
    private String bicSwift;

    /** IBAN (mod-97 validated when IBAN-shaped) or raw domestic account number. */
    @Column(name = "iban_or_account_number", nullable = false, length = 34)
    private String ibanOrAccountNumber;

    @Column(name = "account_holder_name", nullable = false, length = 140)
    private String accountHolderName;

    /** ISO-3166 alpha-2 ({@code ^[A-Z]{2}$}); shape enforced by the service. */
    @Column(name = "bank_country", nullable = false, length = 2)
    private String bankCountry;

    /** Correspondent BIC for cross-border SWIFT payouts, or NULL. */
    @Column(name = "intermediary_bic", length = 11)
    private String intermediaryBic;

    /** Provider-stamped verdict; never operator-typed (V012 default UNVERIFIED). */
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 20)
    private BankVerificationStatus verificationStatus = BankVerificationStatus.UNVERIFIED;

    /** Loose reference to the {@code partner_document} evidence upload (V010), or NULL. */
    @Column(name = "verification_evidence_doc_id")
    private Long verificationEvidenceDocId;

    /** Date the verification verdict landed; NULL while UNVERIFIED. */
    @Column(name = "verification_date")
    private LocalDate verificationDate;

    /** TRUE for the payout account of record in its currency (one per currency). */
    @Column(name = "is_primary", nullable = false)
    private boolean primaryAccount;

    /** SWIFT charge bearer (OUR/BEN/SHA) or NULL for domestic rails. */
    @Enumerated(EnumType.STRING)
    @Column(name = "swift_charge_bearer", length = 3)
    private SwiftChargeBearer swiftChargeBearer;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 15)
    private BankAccountPurpose purpose = BankAccountPurpose.PAYOUT;

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

    public BankAccountEntity() {
        // JPA
    }

    @jakarta.persistence.PrePersist
    void onPersist() {
        if (recordedAt == null) {
            // Defensive default matching the V012 column DEFAULT. Truncated to
            // MICROS for the same reason as ContactEntity.onPersist: both
            // PostgreSQL and H2 store TIMESTAMP at microsecond precision and
            // ROUND nanosecond Instants — an un-truncated value can round UP in
            // the database, silently breaking bitemporal predicates and the
            // ADR-007 audit hash chain (Slice 1 lesson).
            recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
        if (validFrom == null) {
            // A bank-account fact has no meaningful existence before capture —
            // same default as contacts (unlike the partner aggregate's EPOCH).
            validFrom = recordedAt;
        }
    }

    /** Adapt this row to the canonical {@link BankAccountView} wire DTO. */
    public BankAccountView toView() {
        return new BankAccountView(
                id,
                currency,
                bankName,
                bicSwift,
                ibanOrAccountNumber,
                accountHolderName,
                bankCountry,
                intermediaryBic,
                verificationStatus == null ? null : verificationStatus.name(),
                verificationEvidenceDocId,
                verificationDate,
                primaryAccount,
                swiftChargeBearer == null ? null : swiftChargeBearer.name(),
                purpose == null ? null : purpose.name(),
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

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getBankName() {
        return bankName;
    }

    public void setBankName(String bankName) {
        this.bankName = bankName;
    }

    public String getBicSwift() {
        return bicSwift;
    }

    public void setBicSwift(String bicSwift) {
        this.bicSwift = bicSwift;
    }

    public String getIbanOrAccountNumber() {
        return ibanOrAccountNumber;
    }

    public void setIbanOrAccountNumber(String ibanOrAccountNumber) {
        this.ibanOrAccountNumber = ibanOrAccountNumber;
    }

    public String getAccountHolderName() {
        return accountHolderName;
    }

    public void setAccountHolderName(String accountHolderName) {
        this.accountHolderName = accountHolderName;
    }

    public String getBankCountry() {
        return bankCountry;
    }

    public void setBankCountry(String bankCountry) {
        this.bankCountry = bankCountry;
    }

    public String getIntermediaryBic() {
        return intermediaryBic;
    }

    public void setIntermediaryBic(String intermediaryBic) {
        this.intermediaryBic = intermediaryBic;
    }

    public BankVerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public void setVerificationStatus(BankVerificationStatus verificationStatus) {
        this.verificationStatus = verificationStatus;
    }

    public Long getVerificationEvidenceDocId() {
        return verificationEvidenceDocId;
    }

    public void setVerificationEvidenceDocId(Long verificationEvidenceDocId) {
        this.verificationEvidenceDocId = verificationEvidenceDocId;
    }

    public LocalDate getVerificationDate() {
        return verificationDate;
    }

    public void setVerificationDate(LocalDate verificationDate) {
        this.verificationDate = verificationDate;
    }

    public boolean isPrimaryAccount() {
        return primaryAccount;
    }

    public void setPrimaryAccount(boolean primaryAccount) {
        this.primaryAccount = primaryAccount;
    }

    public SwiftChargeBearer getSwiftChargeBearer() {
        return swiftChargeBearer;
    }

    public void setSwiftChargeBearer(SwiftChargeBearer swiftChargeBearer) {
        this.swiftChargeBearer = swiftChargeBearer;
    }

    public BankAccountPurpose getPurpose() {
        return purpose;
    }

    public void setPurpose(BankAccountPurpose purpose) {
        this.purpose = purpose;
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
