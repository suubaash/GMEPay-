package com.gme.pay.settlement.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.settlement.outbox.OutboxPublisher;
import com.gme.pay.settlement.scheduler.CorridorReconScheduler;
import com.gme.pay.settlement.scheduler.ReconScheduler;
import com.gme.pay.settlement.scheduler.SettlementGenerationScheduler;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
 * T3-11 defect 3: <b>a second instance cannot double-publish</b>.
 *
 * <p>Two halves, because either alone would pass while the gap stayed open:
 *
 * <ol>
 *   <li><b>The mechanism works.</b> Against a real H2 datasource with the real Flyway migration set
 *       applied — so V014 is proven to create a usable {@code shedlock} table on top of V001–V013 —
 *       the {@link ShedLockConfig} provider grants the lock once and refuses the second holder. That
 *       second holder is the second replica.</li>
 *   <li><b>Every scheduled job actually uses it.</b> A working provider that no job is annotated
 *       with protects nothing, and this service's seven jobs include the three that transmit
 *       settlement files. Enumerated by reflection so a job added later without a lock fails this
 *       test rather than shipping.</li>
 * </ol>
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
    @DisplayName("a second instance is refused the lock while the first holds it")
    void secondInstanceCannotAcquireHeldLock() {
        LockConfiguration config = lockFor("SettlementOutboxPublisher_publishPending");

        Optional<SimpleLock> firstReplica = provider.lock(config);
        assertThat(firstReplica)
                .as("the first replica must get the lock — otherwise nothing ever drains")
                .isPresent();

        // This is the whole gap in one assertion. Before T3-11 there was no lock at all, so this
        // second replica would have read the same unpublished outbox rows and published them again.
        Optional<SimpleLock> secondReplica = provider.lock(config);
        assertThat(secondReplica)
                .as("a second replica must be refused while the first holds the lock")
                .isEmpty();

        firstReplica.get().unlock();

        assertThat(provider.lock(config))
                .as("after release the next tick must be able to run — a lock that never frees is "
                        + "a stopped queue, which is its own outage")
                .isPresent();
    }

    @Test
    @DisplayName("locks are per job: one job's lock does not block a different job")
    void differentJobsDoNotBlockEachOther() {
        Optional<SimpleLock> outbox = provider.lock(lockFor("SettlementOutboxPublisher_publishPending"));
        assertThat(outbox).isPresent();

        // Named locks, not one global one. A long 05:00 settlement generation must not be able to
        // stop the 1-second outbox drain, and vice versa.
        assertThat(provider.lock(lockFor("SettlementGeneration_morningRequest"))).isPresent();
    }

    @Test
    @DisplayName("every @Scheduled method in this service carries a uniquely-named @SchedulerLock")
    void everyScheduledJobIsLocked() {
        List<Class<?>> schedulers = List.of(
                OutboxPublisher.class,
                ReconScheduler.class,
                CorridorReconScheduler.class,
                SettlementGenerationScheduler.class);

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
                .as("this service is expected to run seven scheduled jobs; if that changed, the "
                        + "scheduler pool size in application.yml needs revisiting too")
                .isEqualTo(7);
        assertThat(unlocked)
                .as("an unlocked scheduled job in THIS service means duplicate settlement files or "
                        + "duplicate recon rows on a second replica")
                .isEmpty();
    }

    private static LockConfiguration lockFor(String name) {
        return new LockConfiguration(Instant.now(), name, Duration.ofMinutes(5), Duration.ZERO);
    }
}
