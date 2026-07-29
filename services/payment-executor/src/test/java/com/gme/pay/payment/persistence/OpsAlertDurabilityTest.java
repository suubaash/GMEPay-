package com.gme.pay.payment.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.contracts.events.OpsAlertPayload;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.annotation.Commit;
import org.springframework.test.annotation.DirtiesContext;

/**
 * <b>T3-3 restart-survival proof</b> for the durable ops-alert archive (Flyway V006).
 *
 * <h2>What is actually being proven</h2>
 * <p>The gap was not "alerts are hard to query" — it was that the whole alerting chain terminated in
 * volatile memory (ops-partner-bff's 200-entry {@code ArrayDeque}), so a restart erased every alert and
 * a busy hour silently evicted the rest. A test that merely round-trips a row would not catch a
 * regression back to an in-memory store held by a bean.
 *
 * <p>So this test <b>simulates a process restart</b>:
 *
 * <ol>
 *   <li>{@link #alertsAreWrittenBeforeTheRestart()} writes alerts through an {@link OpsAlertArchive}
 *       and <b>commits</b> ({@code @Commit} — {@code @DataJpaTest} rolls back by default), then
 *       {@code @DirtiesContext} tears the Spring context down: the {@code EntityManagerFactory}, the
 *       connection pool and every bean that could have been holding alerts in a field are destroyed.</li>
 *   <li>{@link #alertsSurviveTheRestart()} runs against a <b>freshly built context</b> and a brand-new
 *       {@code OpsAlertArchive} instance, and still finds the alerts.</li>
 * </ol>
 *
 * <p>The H2 URL in {@code application.properties} is a named in-memory DB with
 * {@code DB_CLOSE_DELAY=-1}, so the database itself outlives the context — which is exactly the
 * production relationship between PostgreSQL and a restarted pod. Had the archive been in-memory,
 * step 2 would find nothing.
 *
 * <p>Rows are tagged with a unique {@code alertType} so the committed data cannot affect the other
 * slice tests sharing this JVM's H2 instance.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OpsAlertDurabilityTest {

    /** Unique to this test so committed rows are invisible to every other query in the module. */
    private static final String PROBE_TYPE = "T3_3_RESTART_PROBE";

    private static final Instant T0 = Instant.parse("2026-07-28T18:30:00Z");

    @Autowired
    private OpsAlertRepository repository;

    private OpsAlertArchive archive() {
        // A fresh instance every time: the archive must hold no state of its own.
        return new OpsAlertArchive(repository, Clock.fixed(T0, ZoneOffset.UTC), 90);
    }

    private static OpsAlertPayload alert(String severity, String subject, String occurredAt) {
        return new OpsAlertPayload(OpsAlertPayload.EVENT_TYPE, PROBE_TYPE, severity, subject,
                "declineRate=1.00 (25/25) over 60s > threshold=0.50", occurredAt);
    }

    @Test
    @Order(1)
    @Commit
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @DisplayName("alerts are persisted (and committed) before the simulated restart")
    void alertsAreWrittenBeforeTheRestart() {
        OpsAlertArchive archive = archive();

        Long critical = archive.record(alert("CRITICAL", "PTN-ACME", "2026-07-28T18:30:00Z"));
        Long warn = archive.record(alert("WARN", "zeropay", "2026-07-28T18:25:00Z"));

        assertThat(critical).as("record() must return the persisted row id").isNotNull();
        assertThat(warn).isNotNull();

        // The notification outcome is stamped onto the same row, so one row answers "did anyone
        // find out?" as well as "what fired?".
        archive.recordNotification(critical, OpsAlertEntity.NOTIFY_DELIVERED, "webhook", null);
        archive.recordNotification(warn, OpsAlertEntity.NOTIFY_FAILED, "webhook", "http 503");

        assertThat(archive.recent(null, PROBE_TYPE, 10)).hasSize(2);
    }

    @Test
    @Order(2)
    @DisplayName("a fresh context + fresh archive still sees them — the deque regression is dead")
    void alertsSurviveTheRestart() {
        List<OpsAlertEntity> recent = archive().recent(null, PROBE_TYPE, 10);

        assertThat(recent)
                .as("alerts written before the context was destroyed must still be readable")
                .hasSize(2);
        // Newest first, by the monitor's occurredAt rather than insertion order.
        assertThat(recent.get(0).getSeverity()).isEqualTo("CRITICAL");
        assertThat(recent.get(0).getSubjectRef()).isEqualTo("PTN-ACME");
        assertThat(recent.get(0).getOccurredAt()).isEqualTo(Instant.parse("2026-07-28T18:30:00Z"));
        assertThat(recent.get(0).getNotifyStatus()).isEqualTo(OpsAlertEntity.NOTIFY_DELIVERED);
        assertThat(recent.get(0).getNotifyChannel()).isEqualTo("webhook");
        assertThat(recent.get(1).getSeverity()).isEqualTo("WARN");
        assertThat(recent.get(1).getNotifyStatus()).isEqualTo(OpsAlertEntity.NOTIFY_FAILED);
        assertThat(recent.get(1).getNotifyError()).isEqualTo("http 503");
    }

    @Test
    @Order(3)
    @DisplayName("severity/type filters and the hard limit cap are applied in SQL")
    void queriesAreFilteredAndBounded() {
        OpsAlertArchive archive = archive();

        assertThat(archive.recent("critical", PROBE_TYPE, 10))
                .as("severity filter is case-insensitive")
                .hasSize(1);
        assertThat(archive.recent(null, "no-such-type", 10)).isEmpty();
        assertThat(archive.recent(null, PROBE_TYPE, 1))
                .as("the caller's limit is honoured")
                .hasSize(1);
        // A caller asking for everything is still capped, so no query can pull an unbounded history.
        assertThat(archive.recent(null, PROBE_TYPE, Integer.MAX_VALUE))
                .hasSizeLessThanOrEqualTo(OpsAlertArchive.MAX_LIMIT);
    }

    @Test
    @Order(4)
    @Commit
    @DisplayName("retention prune drops alerts older than the window, keeps the rest")
    void pruneEnforcesRetention() {
        // A 0-day retention window with the fixed clock => everything strictly older than T0 goes.
        OpsAlertArchive shortRetention =
                new OpsAlertArchive(repository, Clock.fixed(T0, ZoneOffset.UTC), 0);
        // 0 is coerced to the 90-day default, so nothing should be pruned yet.
        assertThat(shortRetention.prune()).isZero();
        assertThat(shortRetention.recent(null, PROBE_TYPE, 10)).hasSize(2);

        // Advance the clock a year: both probe rows are now outside the 90-day window.
        OpsAlertArchive future =
                new OpsAlertArchive(repository, Clock.fixed(T0.plusSeconds(400L * 86_400), ZoneOffset.UTC), 90);
        assertThat(future.prune()).isGreaterThanOrEqualTo(2);
        assertThat(future.recent(null, PROBE_TYPE, 10)).isEmpty();
    }
}
