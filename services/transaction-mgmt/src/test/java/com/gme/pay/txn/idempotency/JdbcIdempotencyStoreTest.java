package com.gme.pay.txn.idempotency;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * <b>The money-path property:</b> an idempotency key honoured on replica A is not re-executed on
 * replica B.
 *
 * <p>Runs against the real H2 (PostgreSQL-mode) datasource with the <b>full Flyway migration set
 * applied</b>, so V013 is proved to apply on top of V001-V012 and to produce the table the store
 * expects — the same standard the ShedLock migrations were held to. Two {@link JdbcIdempotencyStore}
 * instances over one {@link JdbcTemplate} <em>are</em> the two replicas: separate objects, separate
 * in-process state, one shared durable store, which is exactly the deployment shape.
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
// see into anyway. Running uncommitted-transaction-free matches TransactionController, which lets
// the transaction insert commit before claiming the key.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcIdempotencyStoreTest {

    private static final Duration TTL = Duration.ofHours(24);

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearTable() {
        jdbc.update("DELETE FROM idempotency_keys");
    }

    private JdbcIdempotencyStore replica(Clock clock) {
        return new JdbcIdempotencyStore(jdbc, TTL, clock);
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

        assertThat(a.putIfAbsent(key, "{\"txnRef\":\"TXN-1\"}"))
                .as("A is the first claimant").isEmpty();

        assertThat(b.get(key))
                .as("B's fast-path replay check must see A's snapshot — this is the read "
                        + "TransactionController does BEFORE creating anything, so seeing it is "
                        + "the difference between replaying and creating a second transaction")
                .contains("{\"txnRef\":\"TXN-1\"}");

        assertThat(b.putIfAbsent(key, "{\"txnRef\":\"TXN-2\"}"))
                .as("and if B does reach the claim, it loses and replays A's snapshot")
                .contains("{\"txnRef\":\"TXN-1\"}");
    }

    @Test
    @DisplayName("the per-JVM store re-executes the same key on B (the defect this closes)")
    void perJvmStoreReExecutesOnB() {
        Clock clock = at("2026-07-30T10:00:00Z");
        IdempotencyStore a = new InMemoryIdempotencyStore(clock, TTL);
        IdempotencyStore b = new InMemoryIdempotencyStore(clock, TTL);
        String key = "key-cross-replica";

        assertThat(a.putIfAbsent(key, "{\"txnRef\":\"TXN-1\"}")).isEmpty();
        assertThat(b.get(key))
                .as("B has never heard of the key, so the retry falls through to a second create")
                .isEmpty();
        assertThat(b.putIfAbsent(key, "{\"txnRef\":\"TXN-2\"}"))
                .as("and B thinks it won too: one retry, two transactions, two different txnRefs")
                .isEmpty();
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    @DisplayName("concurrent duplicates across 8 replicas resolve to EXACTLY ONE winner")
    void concurrentDuplicatesHaveExactlyOneWinner() throws Exception {
        Clock clock = at("2026-07-30T11:00:00Z");
        String key = "key-race";
        int replicas = 8;

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        Set<String> replayed = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(replicas);
        try {
            for (int i = 0; i < replicas; i++) {
                String snapshot = "{\"txnRef\":\"TXN-" + i + "\"}";
                pool.submit(() -> {
                    start.await();
                    Optional<String> loser = replica(clock).putIfAbsent(key, snapshot);
                    if (loser.isEmpty()) {
                        winners.incrementAndGet();
                    } else {
                        replayed.add(loser.get());
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

        assertThat(winners.get())
                .as("the PRIMARY KEY picks the winner, not the application — so this holds across "
                        + "replicas, where no in-process lock could")
                .isEqualTo(1);
        assertThat(replayed)
                .as("every loser replays the same snapshot: the winner's")
                .hasSize(1);
    }

    // ------------------------------------------------------------------ expiry

    @Test
    @DisplayName("expiry is enforced on READ, so a key stops replaying on time even if no sweep ran")
    void expiryEnforcedOnRead() {
        String key = "key-expiring";
        replica(at("2026-07-30T00:00:00Z")).putIfAbsent(key, "day-1");

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
        replica(at("2026-07-30T00:00:00Z")).putIfAbsent(key, "day-1");

        assertThat(replica(at("2026-07-31T00:00:01Z")).putIfAbsent(key, "day-2"))
                .as("a 25-hour-later reuse must not be answered with yesterday's response")
                .isEmpty();
        assertThat(replica(at("2026-07-31T00:00:02Z")).get(key)).contains("day-2");
        assertThat(rowCount()).as("replaced, not duplicated").isEqualTo(1);
    }

    @Test
    @DisplayName("the retention sweep deletes lapsed rows and leaves live ones")
    void retentionSweepDeletesOnlyLapsed() {
        replica(at("2026-07-30T00:00:00Z")).putIfAbsent("old", "snapshot-old");
        replica(at("2026-07-31T09:00:00Z")).putIfAbsent("fresh", "snapshot-fresh");

        int deleted = replica(at("2026-07-31T10:00:00Z")).deleteExpired();

        assertThat(deleted).isEqualTo(1);
        assertThat(replica(at("2026-07-31T10:00:00Z")).get("fresh")).contains("snapshot-fresh");
        assertThat(rowCount())
                .as("without this the table grows by one row per idempotent create, forever")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ schema / wiring guards

    @Test
    @DisplayName("V013 applied in sequence and produced the columns the store uses")
    void migrationProducedTheExpectedTable() {
        List<String> columns = jdbc.queryForList(
                "SELECT LOWER(column_name) FROM information_schema.columns "
                        + "WHERE LOWER(table_name) = 'idempotency_keys'", String.class);

        assertThat(columns).containsExactlyInAnyOrder(
                "idempotency_key", "response_snapshot", "created_at", "expires_at");
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
