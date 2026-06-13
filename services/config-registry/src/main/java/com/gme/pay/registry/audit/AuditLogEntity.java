package com.gme.pay.registry.audit;

import com.gme.pay.audit.AuditEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * JPA-mapped row of the {@code audit_log} table (V006). Stands in for the
 * Spring-free {@link AuditEvent} record at the persistence boundary; the
 * {@link AuditLogService} converts between the two.
 *
 * <h2>Why a separate class</h2>
 *
 * <p>{@code AuditEvent} is a record so Hibernate cannot manage it. Mirroring the
 * same field set on a JPA entity costs a few lines but keeps {@code lib-audit} a
 * pure contracts library — services own the persistence wiring, exactly the
 * pattern {@code PartnerEntity} establishes for {@code Partner}.
 *
 * <h2>JSONB on H2</h2>
 *
 * <p>PostgreSQL stores {@code beforeJsonb} and {@code afterJsonb} as native
 * {@code JSONB}. H2's PostgreSQL-mode accepts the {@code JSONB} keyword in DDL
 * but stores the bytes as plain {@code VARBINARY} — the JPA-level column type
 * here is therefore the raw byte array, which both engines accept. We retain the
 * raw bytes (not a {@code String}) because the hash chain canonicalisation is
 * byte-exact and we do not want a Jackson reconfigured-on-redeploy to silently
 * invalidate prior rows by, say, switching field ordering.
 *
 * <h2>Append-only</h2>
 *
 * <p>Once persisted, an audit row is never re-saved by the application. The Slice
 * 8 hardening pass adds a DB-level INSERT-only role; until then the discipline
 * lives in the application — {@link AuditLogService#publish} is the only writer,
 * and it never re-reads-and-saves an existing row.
 */
@Entity
@Table(name = "audit_log")
public class AuditLogEntity {

    /**
     * Surrogate, the BIGSERIAL declared on the {@code audit_log} table. We let
     * Hibernate manage this one rather than reaching for a sequence-pull pattern
     * (the way {@code PartnerEntity} does) because audit rows are minted on every
     * write and there is no migration story where we need application-controlled
     * IDs.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 64, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64, updatable = false)
    private String aggregateId;

    @Column(name = "actor_id", nullable = false, length = 64, updatable = false)
    private String actorId;

    @Column(name = "actor_ip", length = 45, updatable = false)
    private String actorIp;

    @Column(name = "event_type", nullable = false, length = 64, updatable = false)
    private String eventType;

    /**
     * Raw JSON bytes of the BEFORE snapshot. Mapped as {@code BYTEA} on PostgreSQL
     * and {@code VARBINARY} on H2. {@code @Lob} is intentionally avoided: on
     * PostgreSQL it would route byte[] through Large Objects (OID/BIGINT pointers)
     * which then collides with the BYTEA column type in V006. Hibernate 6 + recent
     * H2 versions accept unbounded byte[] without truncation, so plain byte[] is
     * sufficient on both engines.
     */
    @Column(name = "before_jsonb", updatable = false, columnDefinition = "bytea")
    private byte[] beforeJsonb;

    @Column(name = "after_jsonb", updatable = false, columnDefinition = "bytea")
    private byte[] afterJsonb;

    @Column(name = "prev_hash", nullable = false, length = 32, updatable = false)
    private byte[] prevHash;

    @Column(name = "row_hash", nullable = false, length = 32, updatable = false)
    private byte[] rowHash;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    public AuditLogEntity() {
        // JPA
    }

    /**
     * Build a JPA entity from a fully-sealed {@link AuditEvent} (one that already
     * carries both {@code prevHash} and {@code rowHash}). The {@code id} stays
     * {@code null} so JPA's IDENTITY strategy allocates a fresh BIGSERIAL on
     * INSERT.
     */
    public static AuditLogEntity fromDomain(AuditEvent event) {
        AuditLogEntity e = new AuditLogEntity();
        e.aggregateType = event.aggregateType();
        e.aggregateId = event.aggregateId();
        e.actorId = event.actorId();
        e.actorIp = event.actorIp();
        e.eventType = event.eventType();
        e.beforeJsonb = event.beforeJsonb();
        e.afterJsonb = event.afterJsonb();
        e.prevHash = event.prevHash();
        e.rowHash = event.rowHash();
        e.recordedAt = event.recordedAt();
        return e;
    }

    public AuditEvent toDomain() {
        return new AuditEvent(id, aggregateType, aggregateId, actorId, actorIp, eventType,
                beforeJsonb, afterJsonb, prevHash, rowHash, recordedAt);
    }

    public Long getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getActorId() {
        return actorId;
    }

    public String getActorIp() {
        return actorIp;
    }

    public String getEventType() {
        return eventType;
    }

    public byte[] getBeforeJsonb() {
        return beforeJsonb;
    }

    public byte[] getAfterJsonb() {
        return afterJsonb;
    }

    public byte[] getPrevHash() {
        return prevHash;
    }

    public byte[] getRowHash() {
        return rowHash;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    /**
     * Setter exposed solely for the tamper test in {@code AuditLogTest}, which
     * needs to silently rewrite an existing row's {@code afterJsonb} to prove the
     * verifier catches the drift. Production code never calls this — the table is
     * append-only and Slice 8 will revoke UPDATE at the DB role level entirely.
     */
    public void setAfterJsonb(byte[] afterJsonb) {
        this.afterJsonb = afterJsonb;
    }
}
