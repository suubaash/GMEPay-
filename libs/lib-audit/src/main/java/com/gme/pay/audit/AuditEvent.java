package com.gme.pay.audit;

import java.time.Instant;
import java.util.Objects;

/**
 * A single tamper-evident audit row per ADR-007.
 *
 * <p>One {@code AuditEvent} corresponds to one {@code audit_log} row and to one
 * message on the {@code gmepay.audit.<aggregateType>} Kafka topic (Slice 8 cold sink
 * picks the topic up via Kafka Connect S3 to the MinIO archive bucket).
 *
 * <p>Field semantics:
 * <ul>
 *   <li>{@code id} — {@code BIGSERIAL} surrogate. {@code null} for a freshly-built event
 *       that has not yet been INSERTed; populated by the DB on first persist.</li>
 *   <li>{@code aggregateType} — the bounded-context aggregate name, e.g. {@code "partner"}.
 *       Also drives the Kafka topic name {@code gmepay.audit.<aggregateType>}.</li>
 *   <li>{@code aggregateId} — the surrogate id (or business key) of the row being
 *       audited. The hash chain is per {@code aggregateId} — every aggregate gets its
 *       own chain so verifying one partner's history doesn't require touching another.</li>
 *   <li>{@code actorId} — who made the change. For 4-eyes flows (ADR-008) this is the
 *       proposer on a {@code PROPOSE} event and the approver on an {@code APPLY} event.
 *       System-driven changes use the literal {@code "system"} with the 4-eyes
 *       carve-out from ADR-008.</li>
 *   <li>{@code actorIp} — the client IP recorded at the BFF; {@code null} for system
 *       events. {@code VARCHAR(45)} fits an IPv6 textual address.</li>
 *   <li>{@code eventType} — the verb. Slice 1 uses {@code "PARTNER_SAVED"}; Slice 8
 *       adds the full FSM verbs (PROPOSED / APPROVED / APPLIED / REJECTED / SUSPENDED /
 *       etc).</li>
 *   <li>{@code beforeJsonb} / {@code afterJsonb} — the row state before and after the
 *       write, serialised as JSON UTF-8 bytes (we store the raw bytes rather than a
 *       {@code String} so canonicalisation is unambiguous — the same bytes go into the
 *       hash and into the {@code JSONB} column).</li>
 *   <li>{@code prevHash} / {@code rowHash} — 32-byte SHA-256 outputs, see {@link HashChain}.</li>
 *   <li>{@code recordedAt} — when the event was emitted. The DB has a {@code DEFAULT
 *       now()} fallback for safety but the application sets it explicitly so it goes
 *       into the hash deterministically (the DB's {@code now()} fires at INSERT time
 *       which is AFTER the hash is computed at the application layer).</li>
 * </ul>
 *
 * <p>Records are deliberately framework-free: no JPA, no Jackson annotations, no Spring.
 * Consumers (config-registry, etc.) provide a thin JPA-mapped equivalent and convert at
 * the persistence boundary, exactly the way {@code PartnerEntity} stands in for the
 * {@code Partner} record.
 */
public record AuditEvent(
        Long id,
        String aggregateType,
        String aggregateId,
        String actorId,
        String actorIp,
        String eventType,
        byte[] beforeJsonb,
        byte[] afterJsonb,
        byte[] prevHash,
        byte[] rowHash,
        Instant recordedAt) implements HashChain.AuditEvent {

    public AuditEvent {
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(recordedAt, "recordedAt");
        // prevHash + rowHash may be null only for transient events being built by the
        // writer — the AuditLogService fills them in before persistence. Sealing them
        // here would make the build sequence (compute prev → compute row → insert)
        // awkward without adding value.
    }

    /**
     * Build an event ready for chaining: the writer supplies the prior row's
     * {@code row_hash} (or {@link HashChain#GENESIS} for a first event), the canonical
     * fields, and {@link HashChain#rowHash} fills in this event's own row hash.
     */
    public static AuditEvent newEvent(
            String aggregateType,
            String aggregateId,
            String actorId,
            String actorIp,
            String eventType,
            byte[] beforeJsonb,
            byte[] afterJsonb,
            byte[] prevHash,
            Instant recordedAt) {
        AuditEvent unsealed = new AuditEvent(
                null,
                aggregateType,
                aggregateId,
                actorId,
                actorIp,
                eventType,
                beforeJsonb,
                afterJsonb,
                prevHash,
                null,
                recordedAt);
        byte[] rowHash = HashChain.rowHash(prevHash, unsealed);
        return new AuditEvent(
                null,
                aggregateType,
                aggregateId,
                actorId,
                actorIp,
                eventType,
                beforeJsonb,
                afterJsonb,
                prevHash,
                rowHash,
                recordedAt);
    }
}
