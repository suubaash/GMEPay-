package com.gme.pay.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.payment.dayclose.FxExposureScheduler;
import com.gme.pay.payment.opsrun.LedgerOpsRunRetentionSweeper;
import com.gme.pay.payment.opsrun.MissedLedgerOpsRunMonitor;
import com.gme.pay.payment.replay.RevenuePostingReplayScheduler;
import com.gme.pay.payment.sweeper.AuthorizationExpirySweeper;
import com.gme.pay.payment.sweeper.OpsAlertRetentionSweeper;
import java.io.IOException;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * T3-11 defects 2 and 3 for payment-executor.
 *
 * <p>The job that matters most here is {@code AuthorizationExpirySweeper}: it <b>releases prefunding
 * holds</b>. Two replicas selecting the same expired rows both issue a release, so a partner's float
 * is credited twice for one authorization — a money defect, not a duplicated log line.
 */
class ShedLockTest {

    private LockProvider provider;

    @BeforeEach
    void migrateAndBuildProvider() {
        String url = "jdbc:h2:mem:peshedlock_" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        // DriverManagerDataSource because H2 is runtimeOnly and absent from the test compile classpath.
        DataSource dataSource = new DriverManagerDataSource(url, "sa", "");
        // The full migration set, so V011 is proven to apply on top of V001-V010.
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        provider = new ShedLockConfig().lockProvider(dataSource);
    }

    @Test
    @DisplayName("a second instance cannot run the expiry sweeper while the first holds the lock")
    void secondInstanceCannotDoubleReleaseHolds() {
        LockConfiguration sweeper = new LockConfiguration(
                Instant.now(), "AuthorizationExpirySweeper_sweepExpired",
                Duration.ofMinutes(10), Duration.ZERO);

        Optional<SimpleLock> firstReplica = provider.lock(sweeper);
        assertThat(firstReplica).isPresent();

        assertThat(provider.lock(sweeper))
                .as("a second replica must be refused — otherwise both release the same prefunding "
                        + "holds and a partner's float is credited twice")
                .isEmpty();

        firstReplica.get().unlock();
        assertThat(provider.lock(sweeper)).isPresent();
    }

    @Test
    @DisplayName("every @Scheduled method in this service carries a uniquely-named @SchedulerLock")
    void everyScheduledJobIsLocked() {
        List<String> unlocked = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int scheduled = 0;

        for (Class<?> type : List.of(
                AuthorizationExpirySweeper.class,
                OpsAlertRetentionSweeper.class,
                RevenuePostingReplayScheduler.class,
                FxExposureScheduler.class,
                // T2-5 caveat (e): the two ledger-ops observability jobs. Both are locked for the same
                // reason the retention sweeper is — a missed-run alert raised once per replica is one
                // nobody can threshold, and N bulk DELETEs against one table is contention for nothing.
                MissedLedgerOpsRunMonitor.class,
                LedgerOpsRunRetentionSweeper.class)) {
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
                assertThat(lock.lockAtMostFor()).startsWith("PT");
                assertThat(names.add(lock.name())).isTrue();
            }
        }

        assertThat(scheduled).isEqualTo(6);
        assertThat(unlocked).isEmpty();
    }

    @Test
    @DisplayName("the scheduler pool is sized above the job count so no sweeper can be starved")
    void poolIsSizedAboveTheJobCount() throws IOException {
        Properties shipped = PropertiesLoaderUtils.loadProperties(
                new ClassPathResource("application.properties"));
        String configured = shipped.getProperty("spring.task.scheduling.pool.size");
        assertThat(configured)
                .as("unset means Spring's default of ONE thread for every job in this service")
                .isNotNull();
        assertThat(Integer.parseInt(configured.trim()))
                .as("five always-on fixed-delay jobs plus the lib-errors lag heartbeat share this "
                        + "pool; the missed-run monitor is the one that must never be starved, since "
                        + "it is the job whose purpose is to notice that another job stopped")
                .isGreaterThanOrEqualTo(6);
    }
}
