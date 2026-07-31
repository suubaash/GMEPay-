package com.gme.pay.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The two outbox gauges, and the reasoning behind measuring exactly these two.
 *
 * <table>
 *   <caption>Registered meters</caption>
 *   <tr><th>Meter</th><th>Meaning</th></tr>
 *   <tr>
 *     <td>{@code gmepay.outbox.pending}</td>
 *     <td><b>Backlog depth</b> — rows with {@code published_at IS NULL}. Answers "how much is
 *         waiting".</td>
 *   </tr>
 *   <tr>
 *     <td>{@code gmepay.outbox.oldest.pending.age} (seconds)</td>
 *     <td><b>Lag</b> — age of the oldest unpublished row. Answers "how far behind are we".</td>
 *   </tr>
 * </table>
 *
 * <p>Both, not either. Depth alone cannot distinguish a healthy burst that will drain in two
 * seconds from a stalled publisher holding the same number of rows for an hour; age alone goes to
 * zero the instant one old row is published even if a million remain. Age is the one to alert on
 * — it is the actual promise being broken when an event does not reach a partner — and depth is
 * the one that predicts the database growth this gap is about.
 *
 * <h2>Caching, and why the TTL exists</h2>
 *
 * <p>Micrometer gauges are sampled at scrape time, so without a cache the query would run once per
 * scrape per gauge — and {@code MIN(created_at)} over an unpruned outbox is not free. One cached
 * read per {@link #CACHE_TTL} serves both gauges. A Prometheus scrape interval is typically 15–60
 * seconds, so the cache is invisible in practice and simply bounds the worst case.
 *
 * <h2>Never fatal</h2>
 *
 * <p>Every database interaction is wrapped. A failed probe means the gauges are not registered; a
 * failed read means the last known value is served and {@code NaN} until there is one. Metrics
 * must not be able to take a service down, and an outbox query that starts failing is itself
 * visible as a flat-lining series.
 */
public class OutboxLagGauges {

    private static final Logger log = LoggerFactory.getLogger(OutboxLagGauges.class);

    static final String PENDING_GAUGE = "gmepay.outbox.pending";
    static final String AGE_GAUGE = "gmepay.outbox.oldest.pending.age";

    /** Minimum spacing between database reads, however often Prometheus scrapes. */
    static final Duration CACHE_TTL = Duration.ofSeconds(10);

    private final MeterRegistry meterRegistry;
    private final DataSource dataSource;
    private final AtomicReference<Snapshot> cached = new AtomicReference<>(Snapshot.unknown());

    public OutboxLagGauges(MeterRegistry meterRegistry, DataSource dataSource) {
        this.meterRegistry = meterRegistry;
        this.dataSource = dataSource;
    }

    /**
     * Probes for the table once and registers the gauges only if it is really there.
     *
     * <p>Runs after the context is built, so Flyway has already created the schema. Registering
     * unconditionally and letting the query fail would put a permanent {@code NaN} series on the
     * sixteen services that have no outbox, which is noise that makes a real gap harder to see.
     */
    @PostConstruct
    public void registerIfOutboxPresent() {
        if (!outboxTableExists()) {
            log.debug("no 'outbox' table on this datasource — outbox lag gauges not registered");
            return;
        }
        Gauge.builder(PENDING_GAUGE, this, self -> self.snapshot().pending())
                .description("Outbox rows awaiting publication (published_at IS NULL)")
                .baseUnit("rows")
                .strongReference(true)
                .register(meterRegistry);
        Gauge.builder(AGE_GAUGE, this, self -> self.snapshot().oldestAgeSeconds())
                .description("Age of the oldest unpublished outbox row — the publisher's lag")
                .baseUnit("seconds")
                .strongReference(true)
                .register(meterRegistry);
        log.info("outbox lag gauges registered ({}, {})", PENDING_GAUGE, AGE_GAUGE);
    }

    /** Cached read: at most one database round trip per {@link #CACHE_TTL}. */
    Snapshot snapshot() {
        Snapshot current = cached.get();
        if (current.isFresh()) {
            return current;
        }
        Snapshot refreshed = read();
        cached.set(refreshed);
        return refreshed;
    }

    private Snapshot read() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) AS pending, MIN(created_at) AS oldest "
                             + "FROM outbox WHERE published_at IS NULL")) {
            if (!rs.next()) {
                return Snapshot.of(0, Double.NaN);
            }
            long pending = rs.getLong("pending");
            Timestamp oldest = rs.getTimestamp("oldest");
            // An empty backlog has an age of 0, not NaN: "nothing is waiting" is a measurement,
            // not an absence of one, and an alert on age must see it drop when the queue drains.
            double ageSeconds = (oldest == null)
                    ? 0d
                    : Math.max(0d, Duration.between(oldest.toInstant(), Instant.now()).toMillis() / 1000d);
            return Snapshot.of(pending, ageSeconds);
        } catch (SQLException | RuntimeException e) {
            log.debug("outbox lag read failed; serving last known values: {}", e.toString());
            Snapshot last = cached.get();
            return Snapshot.of(last.pending(), last.oldestAgeSeconds());
        }
    }

    /**
     * True when this datasource has a table named {@code outbox}.
     *
     * <p>Matched case-insensitively against JDBC metadata rather than assumed, because the
     * platform's own datasources disagree on case: PostgreSQL folds unquoted identifiers to lower
     * case while H2 defaults to upper unless {@code DATABASE_TO_LOWER} is set, and the services
     * run on both.
     */
    private boolean outboxTableExists() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            for (String pattern : new String[]{"outbox", "OUTBOX", "%"}) {
                try (ResultSet rs = meta.getTables(null, null, pattern, new String[]{"TABLE"})) {
                    while (rs.next()) {
                        String name = rs.getString("TABLE_NAME");
                        if (name != null && "outbox".equals(name.toLowerCase(Locale.ROOT))) {
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (SQLException | RuntimeException e) {
            log.debug("could not probe for an 'outbox' table; gauges not registered: {}", e.toString());
            return false;
        }
    }

    /** An immutable reading plus the instant it was taken. */
    record Snapshot(long pending, double oldestAgeSeconds, Instant takenAt) {

        static Snapshot unknown() {
            return new Snapshot(0, Double.NaN, Instant.EPOCH);
        }

        static Snapshot of(long pending, double ageSeconds) {
            return new Snapshot(pending, ageSeconds, Instant.now());
        }

        boolean isFresh() {
            return Duration.between(takenAt, Instant.now()).compareTo(CACHE_TTL) < 0;
        }
    }
}
