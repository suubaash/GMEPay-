package com.gme.pay.registry.credential;

import com.gme.pay.contracts.PartnerIpAllowlistView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * JPA-mapped row of the {@code partner_ip_allowlist} table (V026) — one
 * allowed CIDR per row, environment-scoped (Slice 8 Lane B — Credentials).
 *
 * <p>NOT bitemporal: the allowlist is operational reachability config; its
 * history is the ADR-007 audit trail (BEFORE/AFTER snapshots on every bulk
 * replace), not row versioning — see the V026 header. A step-8 save is
 * DELETE-all + INSERT-new inside one transaction
 * ({@link PartnerIpAllowlistService#replaceAllowlist}).
 */
@Entity
@Table(name = "partner_ip_allowlist")
public class PartnerIpAllowlistEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** FK to {@code partners.id} (the V003/V004 BIGINT surrogate). */
    @Column(name = "partner_id", nullable = false, updatable = false)
    private Long partnerId;

    /** The allowed source range in CIDR notation (validated service-side). */
    @Column(name = "cidr", nullable = false, length = 43)
    private String cidr;

    /** Operator-facing label; nullable. */
    @Column(name = "label", length = 120)
    private String label;

    /** V026 CHECK roster: SANDBOX / PRODUCTION. */
    @Column(name = "environment", nullable = false, length = 20)
    private String environment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    public PartnerIpAllowlistEntity() {
        // JPA
    }

    @jakarta.persistence.PrePersist
    void onPersist() {
        if (createdAt == null) {
            // MICROS truncation: stored TIMESTAMP must equal the in-memory
            // value on both PostgreSQL and H2 — same discipline as
            // PartnerSchemeEntity.onPersist (Slice 1 lesson).
            createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
    }

    /** Adapt this row to the canonical {@link PartnerIpAllowlistView} wire DTO. */
    public PartnerIpAllowlistView toView() {
        return new PartnerIpAllowlistView(id, cidr, label, environment, createdAt, createdBy);
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

    public String getCidr() {
        return cidr;
    }

    public void setCidr(String cidr) {
        this.cidr = cidr;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}
