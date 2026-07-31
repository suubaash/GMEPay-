package com.gme.pay.audit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;

/**
 * Durable {@link AuditPublisher} that INSERTs each {@link AuditEvent} into the
 * {@code audit_log} table and maintains the per-aggregate SHA-256 hash chain.
 *
 * <h3>Activation</h3>
 * <p>Annotated {@code @Primary @ConditionalOnBean(DataSource.class)} so it activates
 * automatically whenever a {@link DataSource} bean is present. {@link LogAuditPublisher}
 * remains the no-datasource fallback (it carries no {@code @Component} by itself —
 * consuming services supply it via {@code @Bean @ConditionalOnMissingBean}).
 *
 * <h3>Hash-chain semantics</h3>
 * <p>Before each INSERT the publisher queries for the latest {@code row_hash} for the
 * incoming event's {@code (aggregate_type, aggregate_id)}.  If no prior row exists, the
 * 32-byte {@link HashChain#GENESIS} vector is used as {@code prev_hash}.  The new
 * {@code row_hash} is computed by {@link HashChain#rowHash} over the event's canonical
 * bytes.  The event passed to {@link #publish} must already carry a correctly-computed
 * {@code rowHash} — if it was built via {@link AuditEvent#newEvent} with the correct
 * {@code prevHash} the chain is correct; the publisher treats the stored hashes as
 * authoritative and does NOT recompute them here (doing so would require a SELECT-then-
 * INSERT and would diverge from an event that was pre-hashed externally).
 *
 * <p>In practice callers should use {@link AuditEvent#newEvent} with {@link HashChain#GENESIS}
 * when they do not have the prior hash in hand and let the DB sequence + this publisher
 * verify the chain later; the 4-eyes path (ADR-008) queries the last row_hash before
 * building the event.
 *
 * <h3>Transaction participation</h3>
 * <p>The publisher uses {@link DataSourceUtils#getConnection} so it participates in any
 * Spring-managed transaction already open on the calling thread. This is the correct
 * behaviour when audit is written inside the same transaction as the business write
 * (per ADR-007: the audit row must either both commit or both roll back with the
 * business row). If no transaction is active a fresh auto-committed connection is used.
 *
 * <h3>Failure handling</h3>
 * <p>Per the {@link AuditPublisher} contract, implementations must not throw on
 * backpressure.  A SQL exception is logged at {@code ERROR} level and swallowed so
 * the business write path is never broken by an audit-tier failure.  (In practice
 * this path is inside a shared transaction, so a failure here would roll the whole
 * transaction back — but the contract still demands we not throw since alternative
 * implementations may be out-of-transaction.)
 *
 * <h3>Flyway migration</h3>
 * <p>The DDL is in
 * {@code libs/lib-audit/src/main/resources/db/migration/V0001__audit_log.sql}.
 * Services that adopt this publisher configure Flyway to include that classpath
 * location, e.g.:
 * <pre>
 *   spring.flyway.locations=classpath:db/migration,classpath:db/migration/audit
 * </pre>
 * Services that already have their own {@code audit_log} DDL (e.g. config-registry
 * V006) should NOT add the lib-audit migration path — their existing table is
 * schema-compatible.
 */
public class DbAuditPublisher implements AuditPublisher {

    private static final Logger log = LoggerFactory.getLogger(DbAuditPublisher.class);

    static final String INSERT_SQL =
            "INSERT INTO audit_log "
                    + "(aggregate_type, aggregate_id, actor_id, actor_ip, event_type, "
                    + " before_jsonb, after_jsonb, prev_hash, row_hash, recorded_at, chain_version) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /**
     * Query for the most-recent row_hash for a given (aggregate_type, aggregate_id) —
     * used by {@link #latestRowHash} to seed the chain for a new event without a
     * caller-supplied prevHash.
     */
    static final String LAST_HASH_SQL =
            "SELECT row_hash FROM audit_log "
                    + "WHERE aggregate_type = ? AND aggregate_id = ? "
                    + "ORDER BY id DESC "
                    + "FETCH FIRST 1 ROWS ONLY";

    private final DataSource dataSource;

    public DbAuditPublisher(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void publish(AuditEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.rowHash() == null) {
            // Gap T5-1: this used to INSERT 32 zero bytes as the row hash. That produced a
            // row which satisfied the octet_length = 32 CHECK, looked like a sealed row, and
            // could never be verified — the single worst outcome available, because it is
            // indistinguishable from a forged row that an attacker zero-filled. We now SEAL
            // the event instead of faking its seal: the correct prev_hash is read from the
            // tail of this aggregate's chain and the digest is computed here.
            log.warn("audit: event arrived unsealed (null rowHash) for aggregateType={} "
                            + "aggregateId={} eventType={} — sealing it here rather than storing an "
                            + "unverifiable zero hash; the caller should use AuditEvent.newEvent",
                    event.aggregateType(), event.aggregateId(), event.eventType());
            append(event.aggregateType(), event.aggregateId(), event.actorId(), event.actorIp(),
                    event.eventType(), event.beforeJsonb(), event.afterJsonb(), event.recordedAt());
            return;
        }
        Connection conn = DataSourceUtils.getConnection(dataSource);
        boolean releaseAfterUse = !DataSourceUtils.isConnectionTransactional(conn, dataSource);
        try {
            doInsert(conn, event);
        } catch (SQLException e) {
            log.error("audit: INSERT failed for aggregateType={} aggregateId={} eventType={}",
                    event.aggregateType(), event.aggregateId(), event.eventType(), e);
            // Per AuditPublisher contract: log-and-continue, never throw.
        } finally {
            if (releaseAfterUse) {
                DataSourceUtils.releaseConnection(conn, dataSource);
            }
        }
    }

    /**
     * Append one <b>correctly chained</b> audit row: read the tail of this aggregate's chain
     * for the {@code prev_hash}, seal the event under {@link HashChain#CURRENT_CHAIN_VERSION},
     * and INSERT it. This is the method services without their own JPA audit entity should
     * call — it is the whole ADR-007 tier-1 write in one hop, and it is the only way to get
     * the chain right without hand-rolling the read-then-seal dance at every call site.
     *
     * <p>Runs on the caller's transaction (via {@link DataSourceUtils#getConnection}) so the
     * audit row commits if and only if the business write commits. Callers that need the
     * audit row to survive a rolled-back business transaction (authentication FAILURES are
     * the canonical example — the login attempt is rolled back but must still be logged)
     * must invoke this outside the business transaction or in a new one; see
     * {@code auth-identity}'s {@code AuthAuditService} for that pattern.
     *
     * @param actorId who acted, in the {@link AuditActors} vocabulary. The bare {@code
     *                "system"} literal and blanks are rejected.
     * @return the sealed event that was written (its {@code id} is not populated — the DB
     *         assigns it; callers needing the id should read it back).
     * @throws IllegalArgumentException on an unusable {@code actorId} (gap T5-1). This is a
     *         programming error in the caller, not backpressure, so unlike a SQL failure it
     *         is NOT swallowed.
     */
    public AuditEvent append(String aggregateType,
                             String aggregateId,
                             String actorId,
                             String actorIp,
                             String eventType,
                             byte[] beforeJsonb,
                             byte[] afterJsonb,
                             java.time.Instant recordedAt) {
        byte[] prevHash = latestRowHash(aggregateType, aggregateId);
        AuditEvent sealed = AuditEvent.newEvent(
                aggregateType, aggregateId, actorId, actorIp, eventType,
                beforeJsonb, afterJsonb, prevHash,
                // MICROS truncation: recorded_at is inside the digest, so the in-memory value
                // MUST equal what the TIMESTAMP column stores — DB rounding of a nanosecond
                // Instant would silently break chain verification for that row.
                (recordedAt == null ? java.time.Instant.now() : recordedAt)
                        .truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        publish(sealed);
        return sealed;
    }

    /**
     * Query the most-recent {@code row_hash} stored for {@code (aggregateType, aggregateId)}.
     * Returns {@link HashChain#GENESIS} if no prior row exists. Useful for callers that
     * need to supply the correct {@code prevHash} to {@link AuditEvent#newEvent}.
     */
    public byte[] latestRowHash(String aggregateType, String aggregateId) {
        Connection conn = DataSourceUtils.getConnection(dataSource);
        boolean releaseAfterUse = !DataSourceUtils.isConnectionTransactional(conn, dataSource);
        try {
            try (PreparedStatement ps = conn.prepareStatement(LAST_HASH_SQL)) {
                ps.setString(1, aggregateType);
                ps.setString(2, aggregateId);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        byte[] hash = rs.getBytes(1);
                        return hash == null ? HashChain.GENESIS.clone() : hash;
                    }
                    return HashChain.GENESIS.clone();
                }
            }
        } catch (SQLException e) {
            log.error("audit: latestRowHash query failed for aggregateType={} aggregateId={}",
                    aggregateType, aggregateId, e);
            return HashChain.GENESIS.clone();
        } finally {
            if (releaseAfterUse) {
                DataSourceUtils.releaseConnection(conn, dataSource);
            }
        }
    }

    /**
     * Load a chain of events for tamper-detection verification (ascending id order).
     * Returns an immutable list of lightweight {@link ChainRow} objects that implement
     * {@link HashChain.AuditEvent} so they can be passed directly to {@link HashChain#verify}.
     */
    public List<HashChain.AuditEvent> loadChain(String aggregateType, String aggregateId) {
        return loadChainRows(aggregateType, aggregateId).stream()
                .map(r -> (HashChain.AuditEvent) r)
                .toList();
    }

    /**
     * Same as {@link #loadChain} but keeps the row {@code id}s, so a verifier can name the
     * first broken link by its primary key rather than by its position in a list. A row
     * position is useless in an incident ("row 4 of the chain" — of which snapshot?); the
     * {@code id} is what an investigator selects on.
     */
    public List<ChainRow> loadChainRows(String aggregateType, String aggregateId) {
        Connection conn = DataSourceUtils.getConnection(dataSource);
        boolean releaseAfterUse = !DataSourceUtils.isConnectionTransactional(conn, dataSource);
        try {
            String sql = "SELECT id, aggregate_type, aggregate_id, actor_id, actor_ip, event_type, "
                    + "recorded_at, before_jsonb, after_jsonb, prev_hash, row_hash, chain_version "
                    + "FROM audit_log "
                    + "WHERE aggregate_type = ? AND aggregate_id = ? "
                    + "ORDER BY id ASC";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, aggregateType);
                ps.setString(2, aggregateId);
                try (var rs = ps.executeQuery()) {
                    List<ChainRow> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(new ChainRow(
                                rs.getLong(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getString(5),
                                rs.getString(6),
                                rs.getTimestamp(7).toInstant(),
                                rs.getBytes(8),
                                rs.getBytes(9),
                                rs.getBytes(10),
                                rs.getBytes(11),
                                rs.getInt(12)));
                    }
                    return List.copyOf(rows);
                }
            }
        } catch (SQLException e) {
            log.error("audit: loadChain failed for aggregateType={} aggregateId={}",
                    aggregateType, aggregateId, e);
            return List.of();
        } finally {
            if (releaseAfterUse) {
                DataSourceUtils.releaseConnection(conn, dataSource);
            }
        }
    }

    /** Every {@code (aggregate_type, aggregate_id)} pair present in the table, for a full sweep. */
    public List<String[]> listAggregates() {
        Connection conn = DataSourceUtils.getConnection(dataSource);
        boolean releaseAfterUse = !DataSourceUtils.isConnectionTransactional(conn, dataSource);
        try {
            String sql = "SELECT DISTINCT aggregate_type, aggregate_id FROM audit_log "
                    + "ORDER BY aggregate_type, aggregate_id";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 var rs = ps.executeQuery()) {
                List<String[]> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new String[] {rs.getString(1), rs.getString(2)});
                }
                return List.copyOf(out);
            }
        } catch (SQLException e) {
            log.error("audit: listAggregates failed", e);
            return List.of();
        } finally {
            if (releaseAfterUse) {
                DataSourceUtils.releaseConnection(conn, dataSource);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void doInsert(Connection conn, AuditEvent event) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            ps.setString(1, event.aggregateType());
            ps.setString(2, event.aggregateId());
            ps.setString(3, event.actorId());
            if (event.actorIp() == null) {
                ps.setNull(4, Types.VARCHAR);
            } else {
                ps.setString(4, event.actorIp());
            }
            ps.setString(5, event.eventType());
            setBytes(ps, 6, event.beforeJsonb());
            setBytes(ps, 7, event.afterJsonb());
            setBytes(ps, 8, event.prevHash() == null ? HashChain.GENESIS : event.prevHash());
            // No zero-fill fallback: publish() re-seals an unsealed event before it ever
            // reaches here, so a null rowHash at this point is impossible by construction.
            setBytes(ps, 9, Objects.requireNonNull(event.rowHash(), "rowHash"));
            ps.setTimestamp(10, Timestamp.from(event.recordedAt()));
            ps.setInt(11, event.chainVersion());
            ps.executeUpdate();
        }
    }

    private static void setBytes(PreparedStatement ps, int idx, byte[] value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.VARBINARY);
        } else {
            ps.setBytes(idx, value);
        }
    }

    /**
     * Lightweight read-side row view implementing {@link HashChain.AuditEvent} so that
     * chains loaded via {@link #loadChain} can be passed directly to {@link HashChain#verify}.
     *
     * <p>Carries {@code id} and {@code chainVersion} so a verifier can name the offending row
     * by primary key and canonicalise it under the digest it was actually sealed with. Public
     * (it was package-private) because the verifier lives outside this class.
     */
    public record ChainRow(
            long id,
            String aggregateType,
            String aggregateId,
            String actorId,
            String actorIp,
            String eventType,
            java.time.Instant recordedAt,
            byte[] beforeJsonb,
            byte[] afterJsonb,
            byte[] prevHash,
            byte[] rowHash,
            int chainVersion) implements HashChain.AuditEvent {
    }
}
