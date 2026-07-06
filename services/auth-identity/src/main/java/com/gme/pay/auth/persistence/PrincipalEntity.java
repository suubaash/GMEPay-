package com.gme.pay.auth.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * JPA mapping for the {@code principals} table (V002__create_principals_roles_api_keys.sql).
 *
 * A principal is any identity this service can authenticate: a human hub
 * operator, a partner's machine identity, or an internal service account.
 * Roles are linked through the {@code principal_roles} join table and feed
 * the JWT {@code roles} claim (ticket 18.4-G01).
 */
@Entity
@Table(name = "principals")
public class PrincipalEntity {

    /** Kind of identity. Stored as VARCHAR via {@link EnumType#STRING}. */
    public enum Type { OPERATOR, PARTNER, SERVICE }

    /** Lifecycle status of the principal. */
    public enum Status { ACTIVE, LOCKED, DISABLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "principal_type", length = 16, nullable = false)
    private Type type;

    /** Unique login / client identifier. */
    @Column(name = "username", length = 128, nullable = false, unique = true)
    private String username;

    @Column(name = "display_name", length = 255)
    private String displayName;

    /** Owning partner id — set only for {@link Type#PARTNER} principals. */
    @Column(name = "partner_id")
    private Long partnerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status;

    /** Contact email (RBAC user attribute, V003). */
    @Column(name = "email", length = 255)
    private String email;

    /** Last successful login (V003); maintained by the auth flow. */
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /**
     * Salted PBKDF2 password credential (V007) for human operators who log in
     * via {@code POST /v1/auth/login}. Mirrors the api_keys secret columns:
     * hex hash + hex salt + per-row iteration count. All three are null for
     * principals without a local password (partners, service accounts,
     * Keycloak-only operators). See {@link com.gme.pay.auth.domain.SecretHasher}.
     */
    @Column(name = "password_hash", length = 128)
    private String passwordHash;

    @Column(name = "password_salt", length = 64)
    private String passwordSalt;

    @Column(name = "password_iterations")
    private Integer passwordIterations;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Eager: role sets are tiny and always needed when issuing tokens. */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "principal_roles",
            joinColumns = @JoinColumn(name = "principal_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<RoleEntity> roles = new HashSet<>();

    /** Default constructor required by JPA. */
    protected PrincipalEntity() {
    }

    public PrincipalEntity(Type type, String username, String displayName,
                           Long partnerId, Instant createdAt) {
        this.type = type;
        this.username = username;
        this.displayName = displayName;
        this.partnerId = partnerId;
        this.status = Status.ACTIVE;
        this.createdAt = createdAt;
    }

    public void addRole(RoleEntity role) {
        roles.add(role);
    }

    public Long getId() {
        return id;
    }

    public Type getType() {
        return type;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Long getPartnerId() {
        return partnerId;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    /** True when this principal carries a local password credential (V007). */
    public boolean hasPassword() {
        return passwordHash != null && passwordSalt != null && passwordIterations != null;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getPasswordSalt() {
        return passwordSalt;
    }

    public Integer getPasswordIterations() {
        return passwordIterations;
    }

    /** Set (or rotate) the salted PBKDF2 password credential. */
    public void setPasswordCredential(String passwordHash, String passwordSalt, Integer passwordIterations) {
        this.passwordHash = passwordHash;
        this.passwordSalt = passwordSalt;
        this.passwordIterations = passwordIterations;
    }

    public Set<RoleEntity> getRoles() {
        return roles;
    }
}
