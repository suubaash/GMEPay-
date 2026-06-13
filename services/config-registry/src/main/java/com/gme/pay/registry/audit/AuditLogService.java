package com.gme.pay.registry.audit;

import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.AuditPublisher;
import com.gme.pay.audit.HashChain;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service-side wiring of the ADR-007 audit pipeline. Two responsibilities:
 *
 * <ol>
 *   <li><b>Chained persistence.</b> {@link #publish} reads the latest row for the
 *       aggregate to obtain its {@code row_hash}, uses that as the new row's
 *       {@code prev_hash} (falling back to {@link HashChain#GENESIS} for the very
 *       first row of an aggregate), computes the new row's {@code row_hash} via
 *       {@link HashChain#rowHash}, and INSERTs the row.</li>
 *   <li><b>Fan-out.</b> After persistence, forwards the sealed event to the
 *       configured {@link AuditPublisher} — {@code LogAuditPublisher} for Slice 1
 *       deployments, {@code KafkaAuditPublisher} in Slice 8 when Kafka turns on.</li>
 * </ol>
 *
 * <p>Both halves run inside the caller's transaction ({@code @Transactional} with
 * the default {@code Propagation.REQUIRED}) — the audit row commits if and only if
 * the business write commits. This is the regulator's expectation: if a partner
 * write succeeded, an audit row exists; if a partner write rolled back, no
 * dangling audit row exists either.
 *
 * <h2>Slice 1 vs Slice 8</h2>
 *
 * <p>Slice 1 lands tiers 1 (hot DB) and 2 (in-app publisher) of ADR-007's three-tier
 * architecture. The dedicated audit DB + the INSERT-only role + the MinIO cold sink
 * land in Slice 8 hardening. This service stays unchanged across that transition
 * because the dedicated-DB switchover is a JDBC URL change (the entity, the
 * repository, the chain math, the publisher contract — all stable).
 */
@Service
public class AuditLogService {

    private final AuditLogRepository repository;
    private final AuditPublisher publisher;

    public AuditLogService(AuditLogRepository repository, AuditPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    /**
     * Append one row to the audit log for {@code aggregateType}/{@code aggregateId}
     * and fan the sealed event out via the configured {@link AuditPublisher}.
     *
     * @param aggregateType the aggregate kind (e.g. {@code "partner"}).
     * @param aggregateId   the natural key of the aggregate. Slice 1 uses the
     *                      partner business code ({@code partner_code}) because that
     *                      is the identifier callers know — the surrogate {@code id}
     *                      is internal-only. (When the Contract migration drops the
     *                      legacy column we'll switch the chain key to surrogate
     *                      ids in one mechanical refactor.)
     * @param actorId       who proposed/approved/applied the change. {@code "system"}
     *                      is reserved for the auto-suspend / sanctions-hit flows
     *                      that have no human operator.
     * @param actorIp       client IP at the BFF; {@code null} for system events.
     * @param eventType     the verb (Slice 1: {@code "PARTNER_SAVED"}).
     * @param beforeJsonb   pre-write snapshot bytes (UTF-8 JSON) or {@code null} for
     *                      a first-write event.
     * @param afterJsonb    post-write snapshot bytes (UTF-8 JSON).
     * @return the persisted event with id, prevHash, rowHash all populated.
     */
    @Transactional
    public AuditEvent publish(String aggregateType,
                              String aggregateId,
                              String actorId,
                              String actorIp,
                              String eventType,
                              byte[] beforeJsonb,
                              byte[] afterJsonb) {
        // Pull the prior row inside the same transaction so a concurrent writer for
        // the same aggregate sees our row only after commit. The unique-row-per-id
        // BIGSERIAL guarantee, plus per-aggregate ordering on id, gives us a strict
        // serial chain even under concurrent writes — a contended write may briefly
        // see the same prevHash as another, but only one will succeed because the
        // INSERT order on the BIGSERIAL is the order verification walks.
        Optional<AuditLogEntity> prior = repository.findLatestByAggregate(aggregateType, aggregateId);
        byte[] prevHash = prior.map(AuditLogEntity::getRowHash).orElse(HashChain.GENESIS);

        AuditEvent sealed = AuditEvent.newEvent(
                aggregateType,
                aggregateId,
                actorId,
                actorIp,
                eventType,
                beforeJsonb,
                afterJsonb,
                prevHash,
                // MICROS truncation: recorded_at participates in the row hash, so the
                // in-memory value MUST equal what the TIMESTAMP column stores — DB
                // rounding of nanosecond Instants would silently break chain verify.
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));

        AuditLogEntity saved = repository.saveAndFlush(AuditLogEntity.fromDomain(sealed));
        AuditEvent persisted = saved.toDomain();

        // Publish AFTER persistence: the hot DB is the regulator-defensible record.
        // If the publisher fails the business write still proceeds — the publisher
        // contract requires implementations to log-and-swallow on backpressure.
        publisher.publish(persisted);

        return persisted;
    }

    /**
     * Verify the entire chain for the given aggregate. Returns {@code -1} on an
     * intact chain, or the {@code id} of the first bad row.
     *
     * <p>Exposed for tests and for the future {@code POST /v1/admin/audit/verify}
     * endpoint that Slice 8 wires for operator-driven spot-checks. Bypasses any
     * caching by reading directly from the repository in id order.
     */
    public long verifyChain(String aggregateType, String aggregateId) {
        List<AuditLogEntity> rows = repository.findChainByAggregate(aggregateType, aggregateId);
        int idx = HashChain.verify(rows.stream().map(AuditLogEntity::toDomain).toList());
        return idx < 0 ? -1L : rows.get(idx).getId();
    }

    /**
     * Convenience JSON-bytes builder for the partner aggregate. Keeps the JSON
     * canonical (stable key order, no insignificant whitespace) so the bytes that
     * go into the hash chain are the same on every machine that runs the same
     * write path — the canonicalisation does not depend on whatever Jackson
     * defaults happen to be live.
     *
     * <p>Slice 1 has a four-field {@code Partner} record, so a hand-rolled writer
     * is shorter and easier to read than wiring Jackson with strict field-order
     * settings. When Slice 2+ widens the partner aggregate this method moves into
     * a dedicated {@code PartnerJson} canonicaliser; for now it lives here.
     */
    public static byte[] canonicalPartnerJson(Long partnerId, String partnerCode,
                                              String type, String settlementCurrency,
                                              String settlementRoundingMode) {
        // Fixed key order: partnerId, partnerCode, type, settlementCurrency,
        // settlementRoundingMode. Quoted JSON strings; nulls emitted as the
        // unquoted token `null` so we can distinguish "field absent" from "field
        // set to null" in the byte sequence.
        StringBuilder sb = new StringBuilder(96);
        sb.append('{');
        sb.append("\"partnerId\":").append(partnerId == null ? "null" : partnerId).append(',');
        sb.append("\"partnerCode\":").append(jsonString(partnerCode)).append(',');
        sb.append("\"type\":").append(jsonString(type)).append(',');
        sb.append("\"settlementCurrency\":").append(jsonString(settlementCurrency)).append(',');
        sb.append("\"settlementRoundingMode\":").append(jsonString(settlementRoundingMode));
        sb.append('}');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String jsonString(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(s.length() + 2);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
