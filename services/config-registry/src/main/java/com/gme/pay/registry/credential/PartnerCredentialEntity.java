package com.gme.pay.registry.credential;

import com.gme.pay.contracts.PartnerCredentialView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * JPA-mapped row of the {@code partner_credential} ledger (V028) — the
 * registry-side record of one credential issued through auth-identity
 * (Slice 8 Lane B — Credentials).
 *
 * <h2>SECURITY (SEC-09 §4)</h2>
 *
 * <p>This entity carries NO secret material — only the auth-identity public
 * key identifier plus the display-safe prefix / last-4 residue. The salted
 * PBKDF2 hash lives in auth-identity's {@code api_keys} (its V002); the
 * plaintext exists exactly once, inside the issuance response
 * ({@code IssuedCredentialBundle}), and is never persisted anywhere.
 *
 * <h2>Mutability</h2>
 *
 * <p>Unlike the SCD-6 child tables this is an event ledger: lifecycle
 * transitions stamp {@code rotated_at} / {@code revoked_at} + {@code status}
 * in place, and every mutation is audited (ADR-007) — see the V028 header.
 */
@Entity
@Table(name = "partner_credential")
public class PartnerCredentialEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** FK to {@code partners.id} (the V003/V004 BIGINT surrogate). */
    @Column(name = "partner_id", nullable = false, updatable = false)
    private Long partnerId;

    /** V028 CHECK roster: SANDBOX / PRODUCTION. */
    @Column(name = "environment", nullable = false, length = 20)
    private String environment;

    /** V028 CHECK roster: API_KEY / HMAC_SECRET / WEBHOOK_SECRET. */
    @Column(name = "credential_kind", nullable = false, length = 20)
    private String credentialKind;

    /** auth-identity's {@code api_keys.api_key} public identifier — an id, never a secret. */
    @Column(name = "auth_identity_key_id", length = 64)
    private String authIdentityKeyId;

    /** Display prefix of the issued material ({@code pk_test_}, {@code sk_live_}, …). */
    @Column(name = "prefix", nullable = false, length = 20)
    private String prefix;

    /** Last 4 characters of the issued material, for display. */
    @Column(name = "last_4", length = 4)
    private String last4;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** V028 CHECK roster: ACTIVE / ROTATED / REVOKED / EXPIRED. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    public PartnerCredentialEntity() {
        // JPA
    }

    @jakarta.persistence.PrePersist
    void onPersist() {
        if (issuedAt == null) {
            // MICROS truncation — same discipline as the SCD-6 entities.
            issuedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
        if (status == null) {
            status = "ACTIVE";
        }
    }

    /** Adapt this row to the canonical {@link PartnerCredentialView} wire DTO. */
    public PartnerCredentialView toView() {
        return new PartnerCredentialView(
                id,
                environment,
                credentialKind,
                authIdentityKeyId,
                prefix,
                last4,
                issuedAt,
                expiresAt,
                rotatedAt,
                revokedAt,
                status);
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

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getCredentialKind() {
        return credentialKind;
    }

    public void setCredentialKind(String credentialKind) {
        this.credentialKind = credentialKind;
    }

    public String getAuthIdentityKeyId() {
        return authIdentityKeyId;
    }

    public void setAuthIdentityKeyId(String authIdentityKeyId) {
        this.authIdentityKeyId = authIdentityKeyId;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public String getLast4() {
        return last4;
    }

    public void setLast4(String last4) {
        this.last4 = last4;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getRotatedAt() {
        return rotatedAt;
    }

    public void setRotatedAt(Instant rotatedAt) {
        this.rotatedAt = rotatedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
