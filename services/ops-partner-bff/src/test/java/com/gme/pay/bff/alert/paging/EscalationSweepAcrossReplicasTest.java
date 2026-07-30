package com.gme.pay.bff.alert.paging;

import com.gme.pay.bff.alert.InMemoryOpsAlertStore;
import com.gme.pay.bff.alert.JpaOpsAlertStore;
import com.gme.pay.bff.alert.OpsAlertRetentionSweeper;
import com.gme.pay.bff.alert.OpsAlertStore;
import com.gme.pay.bff.alert.OpsAlertView;
import com.gme.pay.bff.persistence.OpsAlertRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * <b>The anti-lock property, tested explicitly.</b>
 *
 * <p>{@link OpsPagingEscalationScheduler#sweep()} must escalate an un-acked CRITICAL alert
 * <em>whichever</em> replica consumed it, and it must stay <b>un-ShedLocked</b>. A lock can only ever
 * subtract escalations — a stuck lock row or an unavailable lock provider would mean no replica sweeps
 * and the alert silently stops escalating, i.e. a missed page during an incident. The duplicate a lock
 * would prevent is already prevented at the pager by the shared {@link PagingCooldown}.
 *
 * <p>This test is the reason the reflection assertions below exist: a future change that "tidies up"
 * by adding {@code @SchedulerLock} to the sweep — which is exactly what an earlier note in that class
 * recommended, and which is now possible because this service has a {@code LockProvider} — must fail
 * here rather than in production at 3am. The contrast with the retention sweeper, which IS locked, is
 * asserted in the same test so neither reads as an oversight.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EscalationSweepAcrossReplicasTest {

    /** occurredAt far in the past, so it is always older than any escalation window. */
    private static final String OLD_CRIT =
            "{\"eventType\":\"ops.alert\",\"alertType\":\"STUCK_TXN\",\"severity\":\"CRITICAL\","
                    + "\"subjectRef\":\"TXN-ESC\",\"detail\":\"stuck\","
                    + "\"occurredAt\":\"2020-01-01T00:00:00Z\"}";

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
        return new JpaOpsAlertStore(repository, txManager, Clock.systemUTC(), Duration.ofDays(90));
    }

    /** Zero cooldown so an escalation is not suppressed by the consume-time page. */
    private OpsPagingDispatcher dispatcher(TestPaging.RecordingPort port, OpsAlertStore store) {
        return TestPaging.dispatcher(port, store, "CRITICAL", Duration.ZERO, Clock.systemUTC());
    }

    // ---------------------------------------------------------------- the property under test

    @Test
    @DisplayName("replica B's sweep escalates an un-acked CRITICAL that replica A consumed")
    void bEscalatesAlertsConsumedByA() {
        JpaOpsAlertStore storeA = replica();
        JpaOpsAlertStore storeB = replica();

        // A consumes the alert (and pages once on consume, through ITS dispatcher).
        TestPaging.RecordingPort portA = new TestPaging.RecordingPort();
        new com.gme.pay.bff.alert.OpsAlertEventHandler(storeA, dispatcher(portA, storeA))
                .handle("TXN-ESC", OLD_CRIT);
        assertThat(portA.pages).hasSize(1);

        // B never saw the record. Its sweep must still escalate it.
        TestPaging.RecordingPort portB = new TestPaging.RecordingPort();
        new OpsPagingEscalationScheduler(storeB, dispatcher(portB, storeB), Duration.ofMinutes(10))
                .sweep();

        assertThat(portB.pages)
                .as("this is what a ShedLock'd sweep would have cost: if only ONE replica may sweep, "
                        + "an un-acked CRITICAL held elsewhere is never escalated at all")
                .hasSize(1);
        assertThat(portB.pages.get(0).subjectRef()).isEqualTo("TXN-ESC");
        assertThat(storeB.recent("CRITICAL", null, 10).get(0).paging().attempts())
                .as("the escalation attempt is recorded on the shared row, not on B's heap")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("with per-JVM stores, B's sweep escalates NOTHING (the defect this closes)")
    void perJvmStoreLeavesBWithNothingToEscalate() {
        OpsAlertStore storeA = new InMemoryOpsAlertStore(200);
        OpsAlertStore storeB = new InMemoryOpsAlertStore(200);
        TestPaging.RecordingPort portA = new TestPaging.RecordingPort();
        new com.gme.pay.bff.alert.OpsAlertEventHandler(storeA, dispatcher(portA, storeA))
                .handle("TXN-ESC", OLD_CRIT);

        TestPaging.RecordingPort portB = new TestPaging.RecordingPort();
        new OpsPagingEscalationScheduler(storeB, dispatcher(portB, storeB), Duration.ofMinutes(10))
                .sweep();

        assertThat(portB.pages)
                .as("B's buffer is empty, which is why locking the sweep to ONE replica used to be "
                        + "the wrong fix — and why the store had to be shared first")
                .isEmpty();
    }

    @Test
    @DisplayName("an ack on A stops B's escalation — the shared row carries the ack")
    void ackOnAStopsEscalationOnB() {
        JpaOpsAlertStore storeA = replica();
        JpaOpsAlertStore storeB = replica();
        TestPaging.RecordingPort portA = new TestPaging.RecordingPort();
        new com.gme.pay.bff.alert.OpsAlertEventHandler(storeA, dispatcher(portA, storeA))
                .handle("TXN-ESC", OLD_CRIT);
        long seq = storeA.recent(null, null, 10).get(0).seq();
        storeA.update(seq, v -> v.withAck(
                new OpsAlertView.Ack("op-1", "handling", "2026-07-30T10:00:00Z")));

        TestPaging.RecordingPort portB = new TestPaging.RecordingPort();
        new OpsPagingEscalationScheduler(storeB, dispatcher(portB, storeB), Duration.ofMinutes(10))
                .sweep();

        assertThat(portB.pages)
                .as("per-JVM, B could not see the ack and kept paging a human about an alert someone "
                        + "was already working")
                .isEmpty();
    }

    @Test
    @DisplayName("every replica sweeps, but the shared cooldown lets only ONE page out")
    void everyReplicaSweepsButOnlyOnePageGoesOut() {
        JpaOpsAlertStore storeA = replica();
        JpaOpsAlertStore storeB = replica();
        storeA.add(new com.gme.pay.contracts.events.OpsAlertPayload(
                "ops.alert", "STUCK_TXN", "CRITICAL", "TXN-ESC", "stuck", "2020-01-01T00:00:00Z"));

        // One shared cooldown = the deployed shape (Redis SET NX EX). A 15-minute window, claimed
        // atomically before each page.
        PagingCooldown shared = new InMemoryPagingCooldown(Clock.systemUTC());
        TestPaging.RecordingPort port = new TestPaging.RecordingPort();
        OpsPagingDispatcher dA = new OpsPagingDispatcher(port, storeA, shared, "CRITICAL",
                Duration.ofMinutes(15), "", Clock.systemUTC());
        OpsPagingDispatcher dB = new OpsPagingDispatcher(port, storeB, shared, "CRITICAL",
                Duration.ofMinutes(15), "", Clock.systemUTC());

        new OpsPagingEscalationScheduler(storeA, dA, Duration.ofMinutes(10)).sweep();
        new OpsPagingEscalationScheduler(storeB, dB, Duration.ofMinutes(10)).sweep();

        assertThat(port.pages)
                .as("duplicate paging is prevented at the PAGER, which is why the sweep does not need "
                        + "— and must not have — a lock")
                .hasSize(1);
    }

    // ------------------------------------------------------- the lock posture, pinned by reflection

    @Test
    @DisplayName("sweep() carries NO @SchedulerLock, and that is a decision, not an omission")
    void escalationSweepIsNotLocked() throws Exception {
        Method sweep = OpsPagingEscalationScheduler.class.getDeclaredMethod("sweep");
        assertThat(sweep.getAnnotation(SchedulerLock.class))
                .as("A lock here could only SUBTRACT escalations: a stuck lock row or an unavailable "
                        + "lock provider means NO replica sweeps and an un-acked CRITICAL stops "
                        + "escalating — a missed page. Duplicates are already prevented by the shared "
                        + "PagingCooldown. If you are here because you added a lock: don't.")
                .isNull();
    }

    @Test
    @DisplayName("the retention sweep IS locked — so the absence above cannot read as laziness")
    void retentionSweepIsLocked() throws Exception {
        Method prune = OpsAlertRetentionSweeper.class.getDeclaredMethod("prune");
        SchedulerLock lock = prune.getAnnotation(SchedulerLock.class);
        assertThat(lock)
                .as("a bulk DELETE gains nothing from running on N replicas (T3-11: idempotent jobs "
                        + "are not treated as exceptions)")
                .isNotNull();
        assertThat(lock.name()).isEqualTo("OpsAlertRetentionSweeper_prune");
    }
}
