package com.gme.pay.auth.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA mapping for the {@code roles} table (V002__create_principals_roles_api_keys.sql).
 *
 * RBAC role catalogue. V002 seeds HUB_ADMIN, HUB_OPERATOR and PARTNER_API;
 * the JWT issuance flow (ticket 18.4-G01) maps these codes into the
 * {@code roles} claim of issued access tokens.
 */
@Entity
@Table(name = "roles")
public class RoleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable role code, e.g. {@code HUB_ADMIN}. Unique. */
    @Column(name = "code", length = 64, nullable = false, unique = true)
    private String code;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Default constructor required by JPA. */
    protected RoleEntity() {
    }

    public RoleEntity(String code, String description, Instant createdAt) {
        this.code = code;
        this.description = description;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
