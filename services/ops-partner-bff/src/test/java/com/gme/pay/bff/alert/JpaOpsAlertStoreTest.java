package com.gme.pay.bff.alert;

import com.gme.pay.bff.persistence.OpsAlertRepository;
import com.gme.pay.contracts.events.OpsAlertPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * <b>The property this closes:</b> two ops-partner-bff replicas see the SAME ops-alert set, with the
 * same alert ids and the same ack state, and the set survives a restart.
 *
 * <p>Runs against the real H2 (PostgreSQL-mode) datasource with the <b>full Flyway set applied</b>, so
 * V001-V003 are proved to apply and to produce the table the store expects. Two
 * {@link JpaOpsAlertStore} instances over one repository <em>are</em> the two replicas: separate
 * objects, separate in-process state, one shared durable store — the same instrument, and the same
 * limits, as {@code transaction-mgmt}'s {@code JdbcIdempotencyStoreTest}. It proves nothing about
 * PostgreSQL-specific locking behaviour; what it proves is that the store's own logic keeps no state
 * in the JVM.
 *
 * <p>Every cross-replica assertion is paired with the same scenario on two
 * {@link InMemoryOpsAlertStore} instances, which must get it <em>wrong</em>. Without that half, a green
 * test would only show that the new code runs — not that the defect it was written for existed.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
// NOT_SUPPORTED, deliberately: @DataJpaTest would otherwise wrap each test in a rolled-back
// transaction, so nothing would ever be committed — and then "replica B sees it" and "it survives a
// restart" would both be untestable, and the concurrent test's other thread could not see in.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JpaOpsAlertStoreTest {

    private static final Duration RETENTION = Duration.ofDays(90);

    @Autowired
    private OpsAlertRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    @BeforeEach
    void clearTable() {
        jdbc.update("DELETE FROM ops_alerts");
    }

    private JpaOpsAlertStore replica() {
        return replica(Clock.systemUTC());
    }

    private JpaOpsAlertStore replica(Clock clock) {
        return new JpaOpsAlertStore(repository, txManager, clock, RETENTION);
    }

    private static OpsAlertPayload critical(String subject) {
        return new OpsAlertPayload(OpsAlertPayload.EVENT_TYPE, "STUCK_TXN", "CRITICAL", subject,
                "stuck", "2020-01-01T00:00:00Z");
    }

    // ------------------------------------------------------------------ the headline property

    @Test
    @DisplayName("an alert consumed by replica A is in replica B's list, with the same seq")
    void alertConsumedOnAIsVisibleOnB() {
        JpaOpsAlertStore a = replica();
        JpaOpsAlertStore b = replica();

        OpsAlertView stored = a.add(critical("TXN-1"));

        assertThat(stored.seq()).as("the DB assigned the id").isPositive();
        assertThat(b.recent(null, null, 10))
                .as("B answers GET /v1/admin/ops/alerts from the same table")
                .extracting(OpsAlertView::seq)
                .containsExactly(stored.seq());
        assertThat(b.find(stored.seq())).isPresent();
    }

    @Test
    @DisplayName("the per-JVM store shows B an EMPTY list, and mints a COLLIDING seq (the defect)")
    void perJvmStoreDivergesAcrossReplicas() {
        OpsAlertStore a = new InMemoryOpsAlertStore(200);
        OpsAlertStore b = new InMemoryOpsAlertStore(200);

        OpsAlertView onA = a.add(critical("TXN-1"));
        assertThat(b.recent(null, null, 10))
                .as("this is what the control tower showed at N>1: a fraction of the fleet's alerts")
                .isEmpty();

        OpsAlertView onB = b.add(critical("TXN-2"));
        assertThat(onB.seq())
                .as("both replicas mint seq=1, so POST /alerts/{id}/ack could ack a DIFFERENT alert "
                        + "than the operator clicked")
                .isEqualTo(onA.seq());
    }

    @Test
    @DisplayName("the alert set survives a 'restart' — a brand-new store instance still sees it")
    void survivesRestart() {
        long seq = replica().add(critical("TXN-RESTART")).seq();

        // A fresh store object holding no state: everything it can know comes from the table.
        JpaOpsAlertStore afterRestart = replica();

        assertThat(afterRestart.find(seq)).isPresent();
        assertThat(afterRestart.recent("CRITICAL", "STUCK_TXN", 10)).hasSize(1);

        OpsAlertStore memory = new InMemoryOpsAlertStore(200);
        memory.add(critical("TXN-RESTART"));
        assertThat(new InMemoryOpsAlertStore(200).recent(null, null, 10))
                .as("the deque lost the whole window on restart")
                .isEmpty();
    }

    @Test
    @DisplayName("an ack on replica A is visible on B — so B stops escalating it")
    void ackOnAIsVisibleOnB() {
        JpaOpsAlertStore a = replica();
        JpaOpsAlertStore b = replica();
        long seq = a.add(critical("TXN-ACK")).seq();

        a.update(seq, v -> v.withAck(new OpsAlertView.Ack("op-1", "handling", "2026-07-30T10:00:00Z")));

        OpsAlertView onB = b.find(seq).orElseThrow();
        assertThat(onB.acked()).as("B sees the acknowledgement").isTrue();
        assertThat(onB.ack().operator()).isEqualTo("op-1");
        assertThat(onB.ack().note()).isEqualTo("handling");

        OpsAlertStore memA = new InMemoryOpsAlertStore(200);
        OpsAlertStore memB = new InMemoryOpsAlertStore(200);
        long memSeq = memA.add(critical("TXN-ACK")).seq();
        memA.update(memSeq, v -> v.withAck(new OpsAlertView.Ack("op-1", null, "2026-07-30T10:00:00Z")));
        assertThat(memB.find(memSeq))
                .as("per-JVM: B never learns the alert was acked and keeps escalating it")
                .isEmpty();
    }

    // ------------------------------------------------------- the read-modify-write that needed a row

    @Test
    @DisplayName("a paging stamp and an ack written from DIFFERENT replicas both survive")
    void pagingStampAndAckDoNotClobberEachOther() {
        JpaOpsAlertStore pager = replica();
        JpaOpsAlertStore acker = replica();
        long seq = pager.add(critical("TXN-BOTH")).seq();

        pager.update(seq, v -> v.withPaging(
                new OpsAlertView.Paging("DELIVERED", "webhook", 1, "2026-07-30T10:00:00Z", null)));
        acker.update(seq, v -> v.withAck(
                new OpsAlertView.Ack("op-2", "ack after page", "2026-07-30T10:01:00Z")));

        OpsAlertView after = replica().find(seq).orElseThrow();
        assertThat(after.paging()).as("the ack did not erase the paging record").isNotNull();
        assertThat(after.paging().status()).isEqualTo("DELIVERED");
        assertThat(after.paging().attempts()).isEqualTo(1);
        assertThat(after.acked()).isTrue();
        assertThat(after.ack().operator()).isEqualTo("op-2");
    }

    @Test
    @DisplayName("a CONCURRENT paging stamp + ack (two threads, two replicas) lose neither field")
    void concurrentPagingAndAckAreSerialised() throws Exception {
        JpaOpsAlertStore pager = replica();
        JpaOpsAlertStore acker = replica();
        long seq = pager.add(critical("TXN-RACE")).seq();

        // This is the scenario that made a Redis hash the wrong shape: two independent writers of one
        // record, each doing a read-modify-write. Here findByIdForUpdate serialises them.
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var pageTask = pool.submit(() -> {
                go.await();
                return pager.update(seq, v -> v.withPaging(new OpsAlertView.Paging(
                        "DELIVERED", "webhook", 1, "2026-07-30T10:00:00Z", null)));
            });
            var ackTask = pool.submit(() -> {
                go.await();
                return acker.update(seq, v -> v.withAck(new OpsAlertView.Ack(
                        "op-3", "concurrent", "2026-07-30T10:00:00Z")));
            });
            go.countDown();
            pageTask.get(20, TimeUnit.SECONDS);
            ackTask.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        OpsAlertView after = replica().find(seq).orElseThrow();
        assertThat(after.paging()).as("paging survived the concurrent ack").isNotNull();
        assertThat(after.paging().status()).isEqualTo("DELIVERED");
        assertThat(after.acked()).as("the ack survived the concurrent paging stamp").isTrue();
    }

    @Test
    @DisplayName("update on an unknown seq is empty, and the mutator is never applied")
    void updateOnUnknownSeqIsEmpty() {
        JpaOpsAlertStore store = replica();
        UnaryOperator<OpsAlertView> boom = v -> {
            throw new AssertionError("mutator must not run for a missing alert");
        };
        assertThat(store.update(999_999L, boom)).isEmpty();
    }

    // ------------------------------------------------------------------------- queries are bounded

    @Test
    @DisplayName("filters are case-insensitive, ordering is newest-first, and the limit is capped")
    void queriesAreFilteredOrderedAndBounded() {
        JpaOpsAlertStore store = replica();
        long first = store.add(critical("A")).seq();
        long second = store.add(new OpsAlertPayload(OpsAlertPayload.EVENT_TYPE, "FLOAT_LOW", "WARN",
                "B", "low", "2020-01-01T00:00:00Z")).seq();
        long third = store.add(critical("C")).seq();

        assertThat(store.recent(null, null, 10))
                .extracting(OpsAlertView::seq)
                .as("newest first, by seq (insertion order) — exactly what the deque gave")
                .containsExactly(third, second, first);
        assertThat(store.recent("critical", null, 10))
                .extracting(OpsAlertView::subjectRef).containsExactly("C", "A");
        assertThat(store.recent(null, "float_low", 10))
                .extracting(OpsAlertView::subjectRef).containsExactly("B");
        assertThat(store.recent("CRITICAL", "FLOAT_LOW", 10)).isEmpty();
        assertThat(store.recent(null, null, 1)).hasSize(1);
        assertThat(store.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("no caller can ask for an unbounded history: 0 and MAX_VALUE both clamp")
    void limitsAreClamped() {
        assertThat(OpsAlertStore.clampLimit(0)).isEqualTo(OpsAlertStore.DEFAULT_LIMIT);
        assertThat(OpsAlertStore.clampLimit(-1)).isEqualTo(OpsAlertStore.DEFAULT_LIMIT);
        assertThat(OpsAlertStore.clampLimit(Integer.MAX_VALUE)).isEqualTo(OpsAlertStore.MAX_LIMIT);
        assertThat(OpsAlertStore.clampLimit(7)).isEqualTo(7);
    }

    // ---------------------------------------------------------------------------------- retention

    @Test
    @DisplayName("the pruner deletes only rows older than the retention window")
    void prunerDeletesOnlyLapsedRows() {
        Instant now = Instant.parse("2026-07-30T10:00:00Z");
        // Written 91 days ago (outside a 90-day window) and 1 day ago (inside it).
        replica(Clock.fixed(now.minus(Duration.ofDays(91)), ZoneOffset.UTC)).add(critical("OLD"));
        replica(Clock.fixed(now.minus(Duration.ofDays(1)), ZoneOffset.UTC)).add(critical("NEW"));

        JpaOpsAlertStore sweeper = replica(Clock.fixed(now, ZoneOffset.UTC));
        assertThat(sweeper.prune()).isEqualTo(1);
        assertThat(sweeper.recent(null, null, 10))
                .extracting(OpsAlertView::subjectRef).containsExactly("NEW");
        assertThat(sweeper.prune()).as("idempotent — a second replica pruning deletes nothing")
                .isZero();
    }

    // ----------------------------------------------------------------- failure posture (add/reads)

    @Test
    @DisplayName("a store failure on add() NEVER throws — a missed page is worse than a missing row")
    void addNeverThrows() {
        OpsAlertRepository broken = mock(OpsAlertRepository.class);
        when(broken.save(any())).thenThrow(new IllegalStateException("db down"));
        JpaOpsAlertStore store = new JpaOpsAlertStore(broken, txManager, Clock.systemUTC(), RETENTION);

        OpsAlertView view = store.add(critical("TXN-DBDOWN"));

        assertThat(view).as("the handler still gets a view, so paging still happens").isNotNull();
        assertThat(view.seq()).as("seq 0 = not stored; update(0) finds nothing and the dispatcher "
                + "stamps the returned view in memory instead").isZero();
        assertThat(view.severity()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("a store failure on a READ propagates — 'no alerts' must never be a lie")
    void readsPropagate() {
        OpsAlertRepository broken = mock(OpsAlertRepository.class);
        when(broken.findRecent(any(), any(), any())).thenThrow(new IllegalStateException("db down"));
        when(broken.findById(any())).thenThrow(new IllegalStateException("db down"));
        JpaOpsAlertStore store = new JpaOpsAlertStore(broken, txManager, Clock.systemUTC(), RETENTION);

        assertThat(catchOf(() -> store.recent(null, null, 10))).isNotNull();
        assertThat(catchOf(() -> store.find(1L))).isNotNull();
    }

    @Test
    @DisplayName("a non-ISO occurredAt is stored VERBATIM, not rejected and not rewritten")
    void nonIsoOccurredAtIsPreserved() {
        JpaOpsAlertStore store = replica();
        OpsAlertView stored = store.add(new OpsAlertPayload(OpsAlertPayload.EVENT_TYPE, "ODD",
                "WARN", "S", "d", "2026-07-30 10:00 KST"));

        assertThat(replica().find(stored.seq()).orElseThrow().occurredAt())
                .as("parsing it into a TIMESTAMP would either drop the alert or rewrite what the "
                        + "producer said; the escalation sweep already handles non-ISO values")
                .isEqualTo("2026-07-30 10:00 KST");
    }

    @Test
    @DisplayName("a producer omitting severity/alertType still gets its alert stored")
    void missingClassificationIsStoredNotDropped() {
        JpaOpsAlertStore store = replica();
        OpsAlertView stored = store.add(
                new OpsAlertPayload(OpsAlertPayload.EVENT_TYPE, null, null, null, null, null));

        Optional<OpsAlertView> found = replica().find(stored.seq());
        assertThat(found).as("NOT NULL columns must never turn a malformed alert into a lost alert")
                .isPresent();
        assertThat(found.orElseThrow().severity()).isEqualTo("UNKNOWN");
        assertThat(found.orElseThrow().alertType()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("the ops_alerts table really is created by Flyway with the columns the store uses")
    void migrationProducesTheExpectedColumns() {
        List<String> columns = jdbc.queryForList(
                "SELECT LOWER(column_name) FROM information_schema.columns "
                        + "WHERE LOWER(table_name) = 'ops_alerts'", String.class);
        assertThat(columns).contains("seq", "alert_type", "severity", "subject_ref", "detail",
                "occurred_at", "created_at", "paging_status", "paging_channel", "paging_attempts",
                "paging_last_at", "paging_detail", "ack_operator", "ack_note", "ack_at");
    }

    private static Throwable catchOf(Runnable r) {
        try {
            r.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }
}
