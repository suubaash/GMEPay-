package com.gme.pay.ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.ledger.outbox.OutboxPublisher;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * T3-11 defect 3, the last service: <b>a second revenue-ledger instance cannot double-publish</b>.
 *
 * <p>Two halves, because either alone would pass while the gap stayed open:
 *
 * <ol>
 *   <li><b>The mechanism works.</b> Against a real H2 datasource with the real Flyway migration set
 *       applied — so V007 is proven to create a usable {@code shedlock} table on top of V001–V006 —
 *       the {@link ShedLockConfig} provider grants the lock once and refuses the second holder. That
 *       second holder is the second replica.</li>
 *   <li><b>Every scheduled job actually uses it.</b> A working provider that no job is annotated
 *       with protects nothing. Enumerated by reflection so a job added later without a lock fails
 *       this test rather than shipping — which is exactly how this service came to be the last
 *       unlocked one in the fleet.</li>
 * </ol>
 *
 * <p>Deliberately a near-copy of settlement-reconciliation's and payment-executor's equivalent. The
 * duplication is the point: the four services' locks must be provably the same mechanism, and a
 * shared helper would let one service's regression hide behind another's green test.
 */
class ShedLockTest {

    private DataSource dataSource;
    private LockProvider provider;

    @BeforeEach
    void migrateAndBuildProvider() {
        // A distinct in-memory database per test so a lock left held cannot leak between them.
        String url = "jdbc:h2:mem:shedlock_" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        // DriverManagerDataSource, not SimpleDriverDataSource: H2 is a runtimeOnly dependency, so the
        // driver class is not on the test COMPILE classpath and can only be reached through
        // DriverManager's service-loader registration.
        dataSource = new DriverManagerDataSource(url, "sa", "");
        // The FULL migration set, not a hand-written CREATE TABLE: this is what proves the new
        // migration applies cleanly in sequence and that its DDL is really what ShedLock expects.
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        provider = new ShedLockConfig().lockProvider(dataSource);
    }

    @Test
    @DisplayName("a second instance is refused the outbox lock while the first holds it")
    void secondInstanceCannotAcquireHeldLock() {
        LockConfiguration config = lockFor("RevenueLedgerOutboxPublisher_publishPending");

        Optional<SimpleLock> firstReplica = provider.lock(config);
        assertThat(firstReplica)
                .as("the first replica must get the lock — otherwise the outbox never drains")
                .isPresent();

        // This is the whole gap in one assertion. Before this change there was no lock at all, so a
        // second replica read the same unpublished rows and published every revenue event again.
        Optional<SimpleLock> secondReplica = provider.lock(config);
        assertThat(secondReplica)
                .as("a second replica must be refused while the first holds the lock")
                .isEmpty();

        firstReplica.get().unlock();

        assertThat(provider.lock(config))
                .as("after release the next tick must be able to run — a lock that never frees is "
                        + "a stopped outbox, which is its own outage")
                .isPresent();
    }

    @Test
    @DisplayName("the lock row lands in the table V007 created, keyed by job name")
    void lockIsPersistedInTheMigratedTable() {
        Optional<SimpleLock> held = provider.lock(lockFor("RevenueLedgerOutboxPublisher_publishPending"));
        assertThat(held).isPresent();

        // Proves the provider is talking to the migrated table rather than succeeding in memory: if
        // V007 created the wrong shape, usingDbTime() would have failed above, and if it created no
        // table at all this row could not exist.
        Integer rows = new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM shedlock WHERE name = ?", Integer.class,
                "RevenueLedgerOutboxPublisher_publishPending");
        assertThat(rows).isEqualTo(1);

        held.get().unlock();
    }

    @Test
    @DisplayName("every @Scheduled method in this service carries a uniquely-named @SchedulerLock")
    void everyScheduledJobIsLocked() {
        List<Class<?>> schedulers = List.of(OutboxPublisher.class);

        List<String> unlocked = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int scheduled = 0;

        for (Class<?> type : schedulers) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getAnnotation(Scheduled.class) == null) {
                    continue;
                }
                scheduled++;
                SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
                if (lock == null) {
                    unlocked.add(type.getSimpleName() + "#" + method.getName());
                    continue;
                }
                assertThat(lock.lockAtMostFor())
                        .as("%s must declare a crash safety net: a holder that dies releases nothing",
                                method.getName())
                        .startsWith("PT");
                assertThat(names.add(lock.name()))
                        .as("lock name '%s' is reused — two jobs sharing a name means one silently "
                                + "blocks the other", lock.name())
                        .isTrue();
            }
        }

        assertThat(scheduled)
                .as("this service is expected to run one scheduled job; if that changed, "
                        + "spring.task.scheduling.pool.size in application.properties needs "
                        + "revisiting too, and the new job needs its own lock name")
                .isEqualTo(1);
        assertThat(unlocked)
                .as("an unlocked scheduled job in THIS service means every revenue-ledger domain "
                        + "event is published once per replica")
                .isEmpty();
    }

    @Test
    @DisplayName("the shipped scheduler pool leaves a thread for the lag heartbeat")
    void schedulerPoolIsSizedForTheJobCountPlusTheHeartbeat() throws IOException {
        Properties shipped = new Properties();
        try (InputStream in = ShedLockTest.class.getResourceAsStream("/application.properties")) {
            assertThat(in).as("application.properties must be on the classpath").isNotNull();
            shipped.load(in);
        }

        // 1 @Scheduled job + lib-errors' SchedulerLagProbe heartbeat. At Spring's default of 1 the
        // probe — whose only purpose is to reveal a starved scheduler — is the thing that starves.
        assertThat(shipped.getProperty("spring.task.scheduling.pool.size"))
                .as("pool size must be set explicitly; Spring's silent default is 1")
                .isNotNull();
        assertThat(Integer.parseInt(shipped.getProperty("spring.task.scheduling.pool.size")))
                .as("pool must cover the scheduled job count plus the lag heartbeat")
                .isGreaterThanOrEqualTo(2);
    }

    private static LockConfiguration lockFor(String name) {
        return new LockConfiguration(Instant.now(), name, Duration.ofMinutes(5), Duration.ZERO);
    }
}
