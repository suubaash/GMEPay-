package com.gme.pay.auth.persistence;

import com.gme.pay.auth.domain.SecretHasher;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA mapping for the {@code api_keys} table (V002__create_principals_roles_api_keys.sql).
 *
 * <p>SECURITY CONVENTION (SEC-09 §4): this entity NEVER holds or persists the
 * plaintext secret. {@link #issue} hashes the plaintext immediately via
 * {@link SecretHasher} (salted PBKDF2-HMAC-SHA256) and only the digest, salt
 * and derivation parameters are stored. Verification goes through
 * {@link #secretMatches(String)} which re-derives and compares in constant
 * time. There is intentionally no getter that could expose secret material
 * beyond the salted hash.</p>
 */
@Entity
@Table(name = "api_keys")
public class ApiKeyEntity {

    /** Credential lifecycle status (rotation overlap uses PENDING_EXPIRY). */
    public enum Status { ACTIVE, PENDING_EXPIRY, REVOKED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Public key identifier presented in X-API-Key. Unique; NOT secret material. */
    @Column(name = "api_key", length = 64, nullable = false, unique = true)
    private String apiKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "principal_id", nullable = false)
    private PrincipalEntity principal;

    /** Hex PBKDF2 digest of the secret — never the plaintext. */
    @Column(name = "secret_hash", length = 128, nullable = false)
    private String secretHash;

    /** Hex per-key random salt used to derive {@link #secretHash}. */
    @Column(name = "secret_salt", length = 64, nullable = false)
    private String secretSalt;

    @Column(name = "hash_algorithm", length = 40, nullable = false)
    private String hashAlgorithm;

    @Column(name = "hash_iterations", nullable = false)
    private int hashIterations;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Default constructor required by JPA. */
    protected ApiKeyEntity() {
    }

    /**
     * Issues a new ACTIVE api-key credential. The plaintext secret is hashed
     * here and discarded — it is never assigned to any field of this entity.
     *
     * @param principal       owning principal (must already be persisted)
     * @param apiKey          public key identifier (unique)
     * @param plaintextSecret the secret to derive the salted hash from
     * @param now             issuance instant recorded as created_at
     */
    public static ApiKeyEntity issue(PrincipalEntity principal, String apiKey,
                                     String plaintextSecret, Instant now) {
        ApiKeyEntity e = new ApiKeyEntity();
        e.principal = principal;
        e.apiKey = apiKey;
        e.secretSalt = SecretHasher.newSaltHex();
        e.hashAlgorithm = SecretHasher.ALGORITHM;
        e.hashIterations = SecretHasher.CURRENT_ITERATIONS;
        e.secretHash = SecretHasher.hashHex(plaintextSecret, e.secretSalt, e.hashIterations);
        e.status = Status.ACTIVE;
        e.createdAt = now;
        return e;
    }

    /**
     * Constant-time verification of a presented candidate secret against the
     * stored salted hash, using the derivation parameters persisted on this row.
     */
    public boolean secretMatches(String candidateSecret) {
        return SecretHasher.matches(candidateSecret, secretSalt, hashIterations, secretHash);
    }

    public void revoke(Instant when) {
        this.status = Status.REVOKED;
        this.revokedAt = when;
    }

    public Long getId() {
        return id;
    }

    public String getApiKey() {
        return apiKey;
    }

    public PrincipalEntity getPrincipal() {
        return principal;
    }

    public String getSecretHash() {
        return secretHash;
    }

    public String getSecretSalt() {
        return secretSalt;
    }

    public String getHashAlgorithm() {
        return hashAlgorithm;
    }

    public int getHashIterations() {
        return hashIterations;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
