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
                    + " before_jsonb, after_jsonb, prev_hash, row_hash, recorded_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

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
            log.warn("audit: event has null rowHash — chain integrity cannot be guaranteed for "
                    + "aggregateType={} aggregateId={}", event.aggregateType(), event.aggregateId());
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
        Connection conn = DataSourceUtils.getConnection(dataSource);
        boolean releaseAfterUse = !DataSourceUtils.isConnectionTransactional(conn, dataSource);
        try {
            String sql = "SELECT event_type, actor_id, recorded_at, before_jsonb, after_jsonb, "
                    + "prev_hash, row_hash "
                    + "FROM audit_log "
                    + "WHERE aggregate_type = ? AND aggregate_id = ? "
                    + "ORDER BY id ASC";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, aggregateType);
                ps.setString(2, aggregateId);
                try (var rs = ps.executeQuery()) {
                    List<HashChain.AuditEvent> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(new ChainRow(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getTimestamp(3).toInstant(),
                                rs.getBytes(4),
                                rs.getBytes(5),
                                rs.getBytes(6),
                                rs.getBytes(7)));
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
            setBytes(ps, 9, event.rowHash() == null ? new byte[HashChain.HASH_LEN] : event.rowHash());
            ps.setTimestamp(10, Timestamp.from(event.recordedAt()));
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
     */
    record ChainRow(
            String eventType,
            String actorId,
            java.time.Instant recordedAt,
            byte[] beforeJsonb,
            byte[] afterJsonb,
            byte[] prevHash,
            byte[] rowHash) implements HashChain.AuditEvent {
    }
}
