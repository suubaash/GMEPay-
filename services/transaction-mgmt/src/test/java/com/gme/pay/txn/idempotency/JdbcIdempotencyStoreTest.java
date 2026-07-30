package com.gme.pay.txn.idempotency;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * <b>The money-path property:</b> an idempotency key honoured on replica A is not re-executed on
 * replica B — and, since V014, <b>the claim is taken before anything is created</b>, so two concurrent
 * duplicates cannot both create.
 *
 * <p>Runs against the real H2 (PostgreSQL-mode) datasource with the <b>full Flyway migration set
 * applied</b>, so V013-V015 are proved to apply in sequence and to produce the table the store expects.
 * Two {@link JdbcIdempotencyStore} instances over one {@link JdbcTemplate} <em>are</em> the two
 * replicas: separate objects, separate in-process state, one shared durable store, which is exactly the
 * deployment shape.
 *
 * <p>Each cross-replica assertion is paired with the same scenario on two
 * {@link InMemoryIdempotencyStore} instances, which must get it <em>wrong</em>. Without that half a
 * green test would only show that the new code runs.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
// NOT_SUPPORTED, deliberately: @DataJpaTest would otherwise wrap each test in a rolled-back
// transaction, which is the one context this store must never run in (a unique violation aborts
// the enclosing transaction on PostgreSQL) and which the concurrency test's other threads could not
// see into anyway. Running uncommitted-transaction-free matches TransactionController, which claims
// the key outside any transaction.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcIdempotencyStoreTest {

    private static final Duration TTL = Duration.ofHours(24);
    private static final Duration CLAIM_TTL = Duration.ofMinutes(2);

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearTable() {
        jdbc.update("DELETE FROM idempotency_keys");
    }

    private JdbcIdempotencyStore replica(Clock clock) {
        return new JdbcIdempotencyStore(jdbc, TTL, CLAIM_TTL, clock);
    }

    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ the headline property

    @Test
    @DisplayName("a key honoured on replica A is REPLAYED on replica B, not re-executed")
    void keyHonouredOnAIsReplayedOnB() {
        Clock clock = at("2026-07-30T10:00:00Z");
        JdbcIdempotencyStore a = replica(clock);
        JdbcIdempotencyStore b = replica(clock);
        String key = "key-cross-replica";

        assertThat(a.claim(key).outcome())
                .as("A is the first claimant").isEqualTo(IdempotencyStore.Outcome.CLAIMED);
        a.complete(key, "{\"txnRef\":\"TXN-1\"}");

        assertThat(b.get(key))
                .as("B's replay read must see A's snapshot — the difference between replaying and "
                        + "creating a second transaction")
                .contains("{\"txnRef\":\"TXN-1\"}");

        IdempotencyStore.Claim onB = b.claim(key);
        assertThat(onB.outcome())
                .as("and if B does reach the claim, it loses and replays")
                .isEqualTo(IdempotencyStore.Outcome.REPLAY);
        assertThat(onB.snapshot()).isEqualTo("{\"txnRef\":\"TXN-1\"}");
    }

    @Test
    @DisplayName("the per-JVM store re-executes the same key on B (the defect this closes)")
    void perJvmStoreReExecutesOnB() {
        Clock clock = at("2026-07-30T10:00:00Z");
        IdempotencyStore a = new InMemoryIdempotencyStore(clock, TTL, CLAIM_TTL);
        IdempotencyStore b = new InMemoryIdempotencyStore(clock, TTL, CLAIM_TTL);
        String key = "key-cross-replica";

        assertThat(a.claim(key).isClaimed()).isTrue();
        a.complete(key, "{\"txnRef\":\"TXN-1\"}");

        assertThat(b.get(key))
                .as("B has never heard of the key, so the retry falls through to a second create")
                .isEmpty();
        assertThat(b.claim(key).outcome())
                .as("and B thinks it won too: one retry, two transactions, two different txnRefs")
                .isEqualTo(IdempotencyStore.Outcome.CLAIMED);
    }

    // ------------------------------------------------------- claim-first: the concurrent duplicate

    @Test
    @DisplayName("concurrent duplicates across 8 replicas resolve to EXACTLY ONE claimant")
    void concurrentDuplicatesHaveExactlyOneWinner() throws Exception {
        Clock clock = at("2026-07-30T11:00:00Z");
        String key = "key-race";
        int replicas = 8;

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger claimed = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        Set<String> replayed = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(replicas);
        try {
            for (int i = 0; i < replicas; i++) {
                pool.submit(() -> {
                    start.await();
                    IdempotencyStore.Claim c = replica(clock).claim(key);
                    switch (c.outcome()) {
                        case CLAIMED -> claimed.incrementAndGet();
                        case IN_FLIGHT -> inFlight.incrementAndGet();
                        case REPLAY -> replayed.add(c.snapshot());
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(claimed.get())
                .as("the PRIMARY KEY picks the claimant, not the application — so this holds across "
                        + "replicas, where no in-process lock could")
                .isEqualTo(1);
        assertThat(inFlight.get())
                .as("everyone else is told IN_FLIGHT (=> 409) and creates NOTHING. Before V014 they "
                        + "had all already created a transaction by this point")
                .isEqualTo(replicas - 1);
        assertThat(replayed).as("nobody can replay yet — the claimant has not answered").isEmpty();
        assertThat(rowCount()).as("exactly one claim row").isEqualTo(1);
    }

    @Test
    @DisplayName("IN_FLIGHT is retryable: once the claimant completes, the duplicate REPLAYS")
    void inFlightBecomesReplay() {
        Clock clock = at("2026-07-30T11:00:00Z");
        String key = "key-inflight";
        JdbcIdempotencyStore a = replica(clock);
        JdbcIdempotencyStore b = replica(clock);

        assertThat(a.claim(key).isClaimed()).isTrue();
        assertThat(b.claim(key).outcome()).isEqualTo(IdempotencyStore.Outcome.IN_FLIGHT);

        a.complete(key, "{\"txnRef\":\"TXN-WINNER\"}");

        IdempotencyStore.Claim retry = b.claim(key);
        assertThat(retry.outcome())
                .as("this is what makes 409 safe rather than a lost payment: the same key retried "
                        + "later gets the winner's answer")
                .isEqualTo(IdempotencyStore.Outcome.REPLAY);
        assertThat(retry.snapshot()).isEqualTo("{\"txnRef\":\"TXN-WINNER\"}");
    }

    @Test
    @DisplayName("a claimed-but-unanswered key has NO snapshot to give (never the empty string)")
    void reservedKeyHasNoSnapshot() {
        Clock clock = at("2026-07-30T11:00:00Z");
        String key = "key-reserved";
        replica(clock).claim(key);

        assertThat(replica(clock).get(key))
                .as("a RESERVED row stores '' in response_snapshot; returning it would answer a "
                        + "duplicate with an empty body")
                .isEmpty();
    }

    @Test
    @DisplayName("release() frees the key immediately, so a failed create never blocks the retry")
    void releaseFreesTheKey() {
        Clock clock = at("2026-07-30T11:00:00Z");
        String key = "key-released";
        JdbcIdempotencyStore a = replica(clock);

        assertThat(a.claim(key).isClaimed()).isTrue();
        a.release(key);

        assertThat(replica(clock).claim(key).outcome())
                .as("the create failed, so the next attempt must be allowed to create — not 409'd "
                        + "for two minutes")
                .isEqualTo(IdempotencyStore.Outcome.CLAIMED);
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("release() does NOT delete a completed key (a replay must survive)")
    void releaseLeavesCompletedKeys() {
        Clock clock = at("2026-07-30T11:00:00Z");
        String key = "key-completed";
        JdbcIdempotencyStore a = replica(clock);
        a.claim(key);
        a.complete(key, "{\"txnRef\":\"TXN-1\"}");

        a.release(key);

        assertThat(a.get(key)).contains("{\"txnRef\":\"TXN-1\"}");
    }

    @Test
    @DisplayName("A LAPSED CLAIM IS RECLAIMABLE — a dead claimant must not lose the payment forever")
    void lapsedClaimIsReclaimable() {
        String key = "key-abandoned";
        // A claimant takes the key and is killed before completing.
        replica(at("2026-07-30T11:00:00Z")).claim(key);

        assertThat(replica(at("2026-07-30T11:01:00Z")).claim(key).outcome())
                .as("inside the claim window the duplicate is still told IN_FLIGHT")
                .isEqualTo(IdempotencyStore.Outcome.IN_FLIGHT);

        assertThat(replica(at("2026-07-30T11:02:01Z")).claim(key).outcome())
                .as("past it the claim is taken over. Without this a pod death would 409 every retry "
                        + "for the whole 24h window, and a payment that can never be retried is a LOST "
                        + "payment — worse than the duplicate the claim prevents. The residual "
                        + "duplicate risk is caught by the V015 unique index on transactions.")
                .isEqualTo(IdempotencyStore.Outcome.CLAIMED);
        assertThat(rowCount()).as("reclaimed, not duplicated").isEqualTo(1);
    }

    @Test
    @DisplayName("completing a lapsed claim does not throw — the transaction still needs an answer")
    void completingALapsedClaimIsTolerated() {
        String key = "key-slow";
        replica(at("2026-07-30T11:00:00Z")).claim(key);
        // The claim lapses and another caller takes it over.
        replica(at("2026-07-30T11:02:01Z")).claim(key);

        // The original, very slow claimant finally answers. It must not blow up: its transaction
        // exists and its caller is owed a 201.
        replica(at("2026-07-30T11:02:02Z")).complete(key, "{\"txnRef\":\"TXN-SLOW\"}");

        assertThat(rowCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ expiry

    @Test
    @DisplayName("expiry is enforced on READ, so a key stops replaying on time even if no sweep ran")
    void expiryEnforcedOnRead() {
        String key = "key-expiring";
        JdbcIdempotencyStore a = replica(at("2026-07-30T00:00:00Z"));
        a.claim(key);
        a.complete(key, "day-1");

        assertThat(replica(at("2026-07-30T23:59:59Z")).get(key))
                .as("inside the 24h window").contains("day-1");
        assertThat(replica(at("2026-07-31T00:00:01Z")).get(key))
                .as("past it — and this must not depend on the retention sweeper, which is "
                        + "ShedLock-guarded and therefore skippable by design")
                .isEmpty();
        assertThat(rowCount())
                .as("the row is still physically present; only its visibility expired")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a lapsed key is reusable, and the claim replaces the stale row")
    void lapsedKeyIsReclaimable() {
        String key = "key-reused-tomorrow";
        JdbcIdempotencyStore day1 = replica(at("2026-07-30T00:00:00Z"));
        day1.claim(key);
        day1.complete(key, "day-1");

        JdbcIdempotencyStore day2 = replica(at("2026-07-31T00:00:01Z"));
        assertThat(day2.claim(key).outcome())
                .as("a 25-hour-later reuse must not be answered with yesterday's response")
                .isEqualTo(IdempotencyStore.Outcome.CLAIMED);
        day2.complete(key, "day-2");

        assertThat(replica(at("2026-07-31T00:00:02Z")).get(key)).contains("day-2");
        assertThat(rowCount()).as("replaced, not duplicated").isEqualTo(1);
    }

    @Test
    @DisplayName("the retention sweep deletes lapsed rows and leaves live ones")
    void retentionSweepDeletesOnlyLapsed() {
        JdbcIdempotencyStore old = replica(at("2026-07-30T00:00:00Z"));
        old.claim("old");
        old.complete("old", "snapshot-old");
        JdbcIdempotencyStore fresh = replica(at("2026-07-31T09:00:00Z"));
        fresh.claim("fresh");
        fresh.complete("fresh", "snapshot-fresh");

        int deleted = replica(at("2026-07-31T10:00:00Z")).deleteExpired();

        assertThat(deleted).isEqualTo(1);
        assertThat(replica(at("2026-07-31T10:00:00Z")).get("fresh")).contains("snapshot-fresh");
        assertThat(rowCount())
                .as("without this the table grows by one row per idempotent create, forever")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ schema / wiring guards

    @Test
    @DisplayName("V013 + V014 applied in sequence and produced the columns the store uses")
    void migrationProducedTheExpectedTable() {
        List<String> columns = jdbc.queryForList(
                "SELECT LOWER(column_name) FROM information_schema.columns "
                        + "WHERE LOWER(table_name) = 'idempotency_keys'", String.class);

        assertThat(columns).containsExactlyInAnyOrder(
                "idempotency_key", "response_snapshot", "created_at", "expires_at",
                "state", "claim_expires_at");
    }

    @Test
    @DisplayName("the DB rejects a state outside {RESERVED, COMPLETED} (V014's CHECK)")
    void stateCheckConstraintIsEnforced() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO idempotency_keys (idempotency_key, response_snapshot, created_at, "
                        + "expires_at, state) VALUES ('bogus', '', ?, ?, 'DONE')",
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now().plus(TTL))))
                .as("the whole control is 'which of these two states is this key in'; a third value "
                        + "would read as neither")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("existing rows default to COMPLETED, so V014 did not orphan the pre-V014 window")
    void preExistingRowsAreCompleted() {
        jdbc.update("INSERT INTO idempotency_keys (idempotency_key, response_snapshot, created_at, "
                        + "expires_at) VALUES ('legacy', 'legacy-snapshot', ?, ?)",
                java.sql.Timestamp.from(Instant.parse("2026-07-30T10:00:00Z")),
                java.sql.Timestamp.from(Instant.parse("2026-07-31T10:00:00Z")));

        assertThat(replica(at("2026-07-30T11:00:00Z")).get("legacy"))
                .as("a row written before V014 must still replay, not look like an unfinished claim")
                .contains("legacy-snapshot");
    }

    @Test
    @DisplayName("the retention sweep is ShedLock-guarded under a unique name")
    void retentionSweepIsLocked() {
        List<Method> scheduled = java.util.Arrays
                .stream(IdempotencyRetentionSweeper.class.getDeclaredMethods())
                .filter(m -> m.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class)
                        != null)
                .toList();

        assertThat(scheduled).hasSize(1);
        SchedulerLock lock = scheduled.get(0).getAnnotation(SchedulerLock.class);
        assertThat(lock)
                .as("N replicas would each run the same bulk DELETE; every @Scheduled method in "
                        + "this repository carries a lock (T3-11 defect 3)")
                .isNotNull();
        assertThat(lock.name()).isEqualTo("IdempotencyRetentionSweeper_sweep");
    }

    private int rowCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys", Integer.class);
        return n == null ? 0 : n;
    }
}
