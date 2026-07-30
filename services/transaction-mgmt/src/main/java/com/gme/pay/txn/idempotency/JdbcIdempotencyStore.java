package com.gme.pay.txn.idempotency;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable, shared {@link IdempotencyStore} backed by the module's own {@code idempotency_keys}
 * table (V013). <b>This is what lifts transaction-mgmt's replica ceiling.</b>
 *
 * <p>The reasoning for a table rather than Redis is written out in full in the migration; the short
 * version is that this is the control preventing a partner retry from becoming a second money
 * transaction, and it must not be able to be <em>unavailable while the money path is available</em>.
 * A row in the same database as the transaction it protects cannot be.
 *
 * <h2>The claim is the primary key</h2>
 * {@link #putIfAbsent} does not read-then-write. It attempts the {@code INSERT} and lets the primary
 * key decide, so two concurrent duplicates — on the same JVM or on different replicas — resolve to
 * exactly one winner without any application-level locking. The loser reads the winner's snapshot.
 *
 * <h2>Expiry</h2>
 * Enforced on read (an expired row is absent) and reaped in two places: opportunistically for the
 * key being claimed, and in bulk by {@link IdempotencyRetentionSweeper}. Enforcing it on read as
 * well as sweeping matters because the sweeper is ShedLock-guarded and therefore skippable — a key
 * must expire on schedule even if the sweep has not run.
 *
 * <h2>Transaction context</h2>
 * These methods are called <b>outside</b> any transaction: {@code TransactionController#create}
 * lets the transaction insert commit first and then claims the key. That is load-bearing, not
 * incidental — on PostgreSQL a unique-violation aborts the enclosing transaction, so a caller that
 * wrapped {@link #putIfAbsent} in its own transaction would find every subsequent statement
 * failing. A future caller that needs to do so must use {@code REQUIRES_NEW}.
 */
public class JdbcIdempotencyStore implements IdempotencyStore {

    private static final String SELECT_LIVE =
            "SELECT response_snapshot FROM idempotency_keys "
                    + "WHERE idempotency_key = ? AND expires_at > ?";

    private static final String DELETE_EXPIRED_KEY =
            "DELETE FROM idempotency_keys WHERE idempotency_key = ? AND expires_at <= ?";

    private static final String INSERT =
            "INSERT INTO idempotency_keys "
                    + "(idempotency_key, response_snapshot, created_at, expires_at) "
                    + "VALUES (?, ?, ?, ?)";

    private static final String DELETE_ALL_EXPIRED =
            "DELETE FROM idempotency_keys WHERE expires_at <= ?";

    private final JdbcTemplate jdbc;
    private final Duration ttl;
    private final Clock clock;

    public JdbcIdempotencyStore(JdbcTemplate jdbc, Duration ttl, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive: " + ttl);
        }
    }

    @Override
    public Optional<String> putIfAbsent(String key, String responseSnapshot) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(responseSnapshot, "responseSnapshot");
        Instant now = clock.instant();

        // Clear this key if a previous window has lapsed, so a legitimate 25-hour-later reuse is
        // not permanently answered with last night's response.
        jdbc.update(DELETE_EXPIRED_KEY, key, Timestamp.from(now));

        try {
            jdbc.update(INSERT, key, responseSnapshot,
                    Timestamp.from(now), Timestamp.from(now.plus(ttl)));
            return Optional.empty();
        } catch (DataIntegrityViolationException lostTheRace) {
            // The primary key picked the other caller. Replay whatever it stored.
            Optional<String> winner = live(key, now);
            // Absent only if the winner's row expired in the gap between the violation and this
            // read (a key at the very end of its window). Reporting "won" is correct: this caller
            // is now the first claimant of a fresh window, and the response it just produced is
            // the one to replay.
            return winner;
        }
    }

    @Override
    public Optional<String> get(String key) {
        Objects.requireNonNull(key, "key");
        return live(key, clock.instant());
    }

    /**
     * Delete every lapsed key. Returns the number removed so the caller can log it.
     *
     * <p>Bulk retention, not correctness: reads already ignore expired rows. Without this the table
     * grows by one row per idempotent request forever.
     */
    public int deleteExpired() {
        return jdbc.update(DELETE_ALL_EXPIRED, Timestamp.from(clock.instant()));
    }

    private Optional<String> live(String key, Instant now) {
        List<String> rows =
                jdbc.queryForList(SELECT_LIVE, String.class, key, Timestamp.from(now));
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }
}
