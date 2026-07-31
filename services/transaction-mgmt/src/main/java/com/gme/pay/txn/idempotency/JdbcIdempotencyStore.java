package com.gme.pay.txn.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable, shared {@link IdempotencyStore} backed by the module's own {@code idempotency_keys} table
 * (V013 + V014). <b>This is what lifts transaction-mgmt's replica ceiling.</b>
 *
 * <p>The reasoning for a table rather than Redis is written out in full in V013; the short version is
 * that this is the control preventing a partner retry from becoming a second money transaction, and it
 * must not be able to be <em>unavailable while the money path is available</em>. A row in the same
 * database as the transaction it protects cannot be.
 *
 * <h2>The claim is the primary key</h2>
 * {@link #claim} does not read-then-write. It attempts the {@code INSERT} of a {@code RESERVED} row and
 * lets the primary key decide, so two concurrent duplicates — on the same JVM or on different replicas
 * — resolve to exactly one winner without any application-level locking. The loser reads the winner's
 * state: {@code COMPLETED} ⇒ replay its snapshot, {@code RESERVED} ⇒ still in flight (409).
 *
 * <h2>Two expiries, and why the second one is the money-safety one</h2>
 * <ul>
 *   <li>{@code expires_at} — the 24h replay window. Enforced <b>on read</b> as well as swept, because
 *       {@link IdempotencyRetentionSweeper} is ShedLock-guarded and therefore skippable: a key must
 *       expire on schedule even if the sweep has not run.</li>
 *   <li>{@code claim_expires_at} — how long a {@code RESERVED} claim may be held. Without it, a
 *       claimant that dies mid-create would answer every retry 409 for 24 hours, and a payment that can
 *       never be retried is a <b>lost</b> payment — worse than the duplicate the claim prevents. With
 *       it, the next caller takes the claim over. The residual risk (the dead claimant had already
 *       inserted its row) is caught by the unique index on
 *       {@code transactions (partner_id, partner_txn_ref)}, V015.</li>
 * </ul>
 *
 * <h2>Transaction context</h2>
 * These methods are called <b>outside</b> any transaction: {@code TransactionController#create} claims,
 * then lets {@code doCreate} commit on its own, then completes. That is load-bearing, not incidental —
 * on PostgreSQL a unique violation aborts the enclosing transaction, so a caller that wrapped
 * {@link #claim} in its own transaction would find every subsequent statement failing. A future caller
 * that needs to do so must use {@code REQUIRES_NEW}.
 */
public class JdbcIdempotencyStore implements IdempotencyStore {

    /** State of a claimed-but-unanswered key. */
    static final String RESERVED = "RESERVED";

    /** State of a key whose response is stored and replayable. */
    static final String COMPLETED = "COMPLETED";

    /**
     * Default {@code claim_expires_at} window. ENGINEERING DEFAULT, not a business commitment: it must
     * comfortably exceed the p99 latency of creating a transaction (a single insert), and an owner
     * should confirm it against real numbers. Too short ⇒ a slow-but-alive claimant can be overtaken
     * (duplicate, caught by V015). Too long ⇒ a retry after a pod death waits this long for its 409 to
     * become a 201.
     */
    public static final Duration DEFAULT_CLAIM_TTL = Duration.ofMinutes(2);

    private static final Logger log = LoggerFactory.getLogger(JdbcIdempotencyStore.class);

    private static final String SELECT_LIVE_COMPLETED =
            "SELECT response_snapshot FROM idempotency_keys "
                    + "WHERE idempotency_key = ? AND expires_at > ? AND state = '" + COMPLETED + "'";

    private static final String SELECT_LIVE_STATE =
            "SELECT state, response_snapshot FROM idempotency_keys "
                    + "WHERE idempotency_key = ? AND expires_at > ?";

    /** Reap this key if its replay window lapsed OR its claim was abandoned. */
    private static final String DELETE_REAPABLE_KEY =
            "DELETE FROM idempotency_keys WHERE idempotency_key = ? "
                    + "AND (expires_at <= ? OR (state = '" + RESERVED + "' AND claim_expires_at <= ?))";

    private static final String INSERT_CLAIM =
            "INSERT INTO idempotency_keys "
                    + "(idempotency_key, response_snapshot, created_at, expires_at, state, "
                    + "claim_expires_at) VALUES (?, '', ?, ?, '" + RESERVED + "', ?)";

    private static final String COMPLETE_CLAIM =
            "UPDATE idempotency_keys SET response_snapshot = ?, state = '" + COMPLETED + "', "
                    + "claim_expires_at = NULL, expires_at = ? "
                    + "WHERE idempotency_key = ? AND state = '" + RESERVED + "'";

    private static final String RELEASE_CLAIM =
            "DELETE FROM idempotency_keys WHERE idempotency_key = ? AND state = '" + RESERVED + "'";

    private static final String DELETE_ALL_EXPIRED =
            "DELETE FROM idempotency_keys WHERE expires_at <= ?";

    private final JdbcTemplate jdbc;
    private final Duration ttl;
    private final Duration claimTtl;
    private final Clock clock;

    public JdbcIdempotencyStore(JdbcTemplate jdbc, Duration ttl, Clock clock) {
        this(jdbc, ttl, DEFAULT_CLAIM_TTL, clock);
    }

    public JdbcIdempotencyStore(JdbcTemplate jdbc, Duration ttl, Duration claimTtl, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.claimTtl = Objects.requireNonNull(claimTtl, "claimTtl");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive: " + ttl);
        }
        if (claimTtl.isNegative() || claimTtl.isZero()) {
            throw new IllegalArgumentException("claimTtl must be positive: " + claimTtl);
        }
    }

    @Override
    public Claim claim(String key) {
        Objects.requireNonNull(key, "key");
        Instant now = clock.instant();

        // Clear this key if a previous window has lapsed (so a legitimate 25-hour-later reuse is not
        // permanently answered with last night's response) or if a claim was abandoned by a caller
        // that died mid-create (so a payment is never permanently un-creatable).
        jdbc.update(DELETE_REAPABLE_KEY, key, Timestamp.from(now), Timestamp.from(now));

        try {
            jdbc.update(INSERT_CLAIM, key, Timestamp.from(now), Timestamp.from(now.plus(ttl)),
                    Timestamp.from(now.plus(claimTtl)));
            return Claim.claimed();
        } catch (DataIntegrityViolationException lostTheRace) {
            // The primary key picked the other caller. Read what it is doing.
            List<Map<String, Object>> rows = jdbc.queryForList(SELECT_LIVE_STATE, key,
                    Timestamp.from(now));
            if (rows.isEmpty()) {
                // The winner's row expired in the gap between the violation and this read. Reporting
                // IN_FLIGHT rather than CLAIMED is the safe half of the choice: the caller retries the
                // same key and creates once, instead of creating now on top of a row we cannot see.
                return Claim.inFlight();
            }
            Object state = rows.get(0).get("state");
            if (COMPLETED.equals(state)) {
                Object snapshot = rows.get(0).get("response_snapshot");
                return snapshot == null ? Claim.inFlight() : Claim.replay(String.valueOf(snapshot));
            }
            return Claim.inFlight();
        }
    }

    @Override
    public void complete(String key, String responseSnapshot) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(responseSnapshot, "responseSnapshot");
        Instant now = clock.instant();
        int updated = jdbc.update(COMPLETE_CLAIM, responseSnapshot,
                Timestamp.from(now.plus(ttl)), key);
        if (updated == 0) {
            // The claim lapsed (a very slow create) or another caller took it over. The transaction
            // WAS created, so the caller must still answer 201 — but a later retry of this key will
            // not replay it, and the V015 unique index is then what stops the retry duplicating the
            // row. Loud, because it means claim-ttl is too short for real latency.
            log.warn("idempotency key={} could not be completed: the RESERVED claim was gone (lapsed "
                    + "or taken over). The transaction was created and is returned, but a retry of "
                    + "this key will NOT replay it — check gmepay.idempotency.claim-ttl against real "
                    + "create latency.", key);
        }
    }

    @Override
    public void release(String key) {
        Objects.requireNonNull(key, "key");
        jdbc.update(RELEASE_CLAIM, key);
    }

    @Override
    public Optional<String> get(String key) {
        Objects.requireNonNull(key, "key");
        // Only a COMPLETED row has a response. A RESERVED row's snapshot is '' and must never be
        // returned as if it were an answer.
        List<String> rows = jdbc.queryForList(SELECT_LIVE_COMPLETED, String.class, key,
                Timestamp.from(clock.instant()));
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
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
}
