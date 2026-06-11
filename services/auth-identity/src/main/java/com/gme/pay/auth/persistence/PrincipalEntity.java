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

    public Set<RoleEntity> getRoles() {
        return roles;
    }
}
