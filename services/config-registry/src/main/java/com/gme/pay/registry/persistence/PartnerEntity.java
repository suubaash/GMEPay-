package com.gme.pay.registry.persistence;

import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
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
 * cannot manage Java records. {@link PartnerStore} converts between this entity
 * and the domain record at the persistence boundary.
 *
 * <p>Mirrors the {@link Partner} fields:
 * partnerId, type, settlementCurrency, settlementRoundingMode.
 * Audit columns ({@code created_at}, {@code updated_at}) are managed by the entity
 * lifecycle callbacks so callers don't need to set them.
 */
@Entity
@Table(name = "partners")
public class PartnerEntity {

    @Id
    @Column(name = "partner_id", nullable = false, length = 32)
    private String partnerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private PartnerType type;

    @Column(name = "settlement_currency", length = 3)
    private String settlementCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_rounding_mode", nullable = false, length = 16)
    private RoundingMode settlementRoundingMode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public PartnerEntity() {
        // JPA
    }

    public PartnerEntity(String partnerId, PartnerType type, String settlementCurrency,
                         RoundingMode settlementRoundingMode) {
        this.partnerId = partnerId;
        this.type = type;
        this.settlementCurrency = settlementCurrency;
        this.settlementRoundingMode = settlementRoundingMode;
    }

    /** Build an entity from the domain record. */
    public static PartnerEntity fromDomain(Partner partner) {
        return new PartnerEntity(
                partner.partnerId(),
                partner.type(),
                partner.settlementCurrency(),
                partner.settlementRoundingMode());
    }

    /** Convert this entity to the domain record. */
    public Partner toDomain() {
        return new Partner(partnerId, type, settlementCurrency, settlementRoundingMode);
    }

    @jakarta.persistence.PrePersist
    void onPersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @jakarta.persistence.PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getPartnerId() {
        return partnerId;
    }

    public void setPartnerId(String partnerId) {
        this.partnerId = partnerId;
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

    public RoundingMode getSettlementRoundingMode() {
        return settlementRoundingMode;
    }

    public void setSettlementRoundingMode(RoundingMode settlementRoundingMode) {
        this.settlementRoundingMode = settlementRoundingMode;
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
}
