package com.gme.pay.registry.changerequest;

import com.gme.pay.changerequest.ChangeRequest;
import com.gme.pay.changerequest.ChangeRequestState;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;

/**
 * JPA-mapped row for {@code change_request} (V005) in config-registry.
 *
 * <p>Separate class from the {@link ChangeRequest} record in lib-change-request
 * because Hibernate cannot manage Java records. {@link ChangeRequestService}
 * converts between this entity and the immutable record at the persistence
 * boundary, the same pattern used for {@code PartnerEntity ↔ Partner}.
 *
 * <h2>Field-set encoding</h2>
 *
 * <p>{@code applies_to_field_set} is modelled in ADR-008 as PostgreSQL
 * {@code TEXT[]}. H2 in PostgreSQL-mode accepts {@code TEXT[]} too but with
 * subtly different JDBC type handling, which would force two code paths.
 * Instead we store it as a single {@code TEXT} column carrying a tab-separated
 * encoding (tabs do not appear in field names) and reconstitute the array via
 * {@link FieldSetConverter}. Aggregate-level queries against
 * {@code applies_to_field_set} are not on the hot path; if they ever land, V0xx
 * can promote the column to native {@code TEXT[]} with an Expand migration.
 */
@Entity
@Table(name = "change_request")
public class ChangeRequestEntity {

    @Id
    @Column(name = "id", updatable = false)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private ChangeRequestState state;

    @Column(name = "proposed_by", nullable = false, length = 64)
    private String proposedBy;

    @Column(name = "proposed_at", nullable = false)
    private Instant proposedAt;

    @Column(name = "approved_by", length = 64)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejected_reason")
    private String rejectedReason;

    @Column(name = "payload_jsonb")
    private String payloadJsonb;

    @Column(name = "applies_to_field_set")
    @Convert(converter = FieldSetConverter.class)
    private String[] appliesToFieldSet;

    public ChangeRequestEntity() {
        // JPA
    }

    /** Build an entity from the immutable domain record. */
    public static ChangeRequestEntity fromDomain(ChangeRequest cr) {
        ChangeRequestEntity e = new ChangeRequestEntity();
        e.id = cr.id();
        e.aggregateType = cr.aggregateType();
        e.aggregateId = cr.aggregateId();
        e.state = cr.state();
        e.proposedBy = cr.proposedBy();
        e.proposedAt = cr.proposedAt() != null ? cr.proposedAt() : Instant.now();
        e.approvedBy = cr.approvedBy();
        e.approvedAt = cr.approvedAt();
        e.rejectedReason = cr.rejectedReason();
        e.payloadJsonb = cr.payloadJsonb();
        e.appliesToFieldSet = cr.appliesToFieldSet() != null
                ? cr.appliesToFieldSet().clone()
                : null;
        return e;
    }

    /** Convert this entity to the immutable domain record. */
    public ChangeRequest toDomain() {
        return new ChangeRequest(
                id,
                aggregateType,
                aggregateId,
                state,
                proposedBy,
                proposedAt,
                approvedBy,
                approvedAt,
                rejectedReason,
                payloadJsonb,
                appliesToFieldSet != null ? appliesToFieldSet.clone() : null);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAggregateType() { return aggregateType; }
    public void setAggregateType(String v) { this.aggregateType = v; }
    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String v) { this.aggregateId = v; }
    public ChangeRequestState getState() { return state; }
    public void setState(ChangeRequestState v) { this.state = v; }
    public String getProposedBy() { return proposedBy; }
    public void setProposedBy(String v) { this.proposedBy = v; }
    public Instant getProposedAt() { return proposedAt; }
    public void setProposedAt(Instant v) { this.proposedAt = v; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String v) { this.approvedBy = v; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant v) { this.approvedAt = v; }
    public String getRejectedReason() { return rejectedReason; }
    public void setRejectedReason(String v) { this.rejectedReason = v; }
    public String getPayloadJsonb() { return payloadJsonb; }
    public void setPayloadJsonb(String v) { this.payloadJsonb = v; }
    public String[] getAppliesToFieldSet() {
        return appliesToFieldSet != null ? appliesToFieldSet.clone() : null;
    }
    public void setAppliesToFieldSet(String[] v) {
        this.appliesToFieldSet = v != null ? v.clone() : null;
    }

    /**
     * Tab-separated TEXT encoding for the field set. Tabs cannot appear in JPA
     * column / Java field names, so the round-trip is lossless. {@code null}
     * round-trips to {@code null}; an empty array round-trips to {@code ""}.
     */
    @Converter
    public static class FieldSetConverter implements AttributeConverter<String[], String> {
        private static final String SEP = "\t";

        @Override
        public String convertToDatabaseColumn(String[] attribute) {
            if (attribute == null) return null;
            return String.join(SEP, attribute);
        }

        @Override
        public String[] convertToEntityAttribute(String dbData) {
            if (dbData == null) return null;
            if (dbData.isEmpty()) return new String[0];
            return dbData.split(SEP, -1);
        }
    }

    @Override
    public String toString() {
        return "ChangeRequestEntity{id=" + id
                + ", aggregateType=" + aggregateType
                + ", aggregateId=" + aggregateId
                + ", state=" + state
                + ", proposedBy=" + proposedBy
                + ", approvedBy=" + approvedBy
                + ", appliesToFieldSet=" + Arrays.toString(appliesToFieldSet)
                + "}";
    }
}
