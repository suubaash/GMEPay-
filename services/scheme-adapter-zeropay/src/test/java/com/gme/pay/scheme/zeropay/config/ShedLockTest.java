package com.gme.pay.scheme.zeropay.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.scheme.zeropay.batch.ZeroPayBatchScheduler;
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
 * T3-11 defect 3 for scheme-adapter-zeropay: <b>a second instance cannot transmit the same batch
 * file twice.</b>
 *
 * <p>This adapter's six KST windows do not compute and store — each generates a ZP00xx file and
 * <em>transfers it to ZeroPay</em>. On two replicas both pods fire the same cron in the same second
 * and the scheme receives the file twice, which nothing downstream can undo.
 */
class ShedLockTest {

    private LockProvider provider;

    @BeforeEach
    void migrateAndBuildProvider() {
        String url = "jdbc:h2:mem:zpshedlock_" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        // DriverManagerDataSource because H2 is runtimeOnly and absent from the test compile classpath.
        DataSource dataSource = new DriverManagerDataSource(url, "sa", "");
        // The full migration set, so V005 is proven to apply on top of V001-V004 and to produce the
        // table ShedLock actually expects — not just a hand-written CREATE TABLE in a test.
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        provider = new ShedLockConfig().lockProvider(dataSource);
    }

    @Test
    @DisplayName("a second replica is refused a batch window's lock while the first holds it")
    void secondReplicaCannotRunTheSameWindow() {
        LockConfiguration zp0061 = new LockConfiguration(
                Instant.now(), "ZeroPayBatch_ZP0061", Duration.ofHours(1), Duration.ZERO);

        Optional<SimpleLock> firstPod = provider.lock(zp0061);
        assertThat(firstPod).isPresent();

        assertThat(provider.lock(zp0061))
                .as("a second pod firing the 05:00 cron must be refused — otherwise ZeroPay receives "
                        + "two settlement requests for the same business day")
                .isEmpty();

        // Windows are independently named: a long ZP0061 must not block the 14:00 ZP0063.
        assertThat(provider.lock(new LockConfiguration(
                Instant.now(), "ZeroPayBatch_ZP0063", Duration.ofHours(1), Duration.ZERO)))
                .isPresent();

        firstPod.get().unlock();
        assertThat(provider.lock(zp0061))
                .as("the lock must free, or the window never runs again")
                .isPresent();
    }

    @Test
    @DisplayName("all six batch windows are locked, under six distinct names")
    void everyBatchWindowIsLocked() {
        List<String> unlocked = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int scheduled = 0;

        for (Method method : ZeroPayBatchScheduler.class.getDeclaredMethods()) {
            if (method.getAnnotation(Scheduled.class) == null) {
                continue;
            }
            scheduled++;
            SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
            if (lock == null) {
                unlocked.add(method.getName());
                continue;
            }
            assertThat(lock.lockAtMostFor()).startsWith("PT");
            assertThat(names.add(lock.name()))
                    .as("lock name '%s' is reused — two windows sharing a name means one silently "
                            + "blocks the other, and ZP0011/ZP0021 are only two minutes apart",
                            lock.name())
                    .isTrue();
        }

        assertThat(scheduled)
                .as("six OUTBOUND generated windows are expected; a new one needs its own lock")
                .isEqualTo(6);
        assertThat(unlocked)
                .as("an unlocked window means ZeroPay receives a duplicated file on a second replica")
                .isEmpty();
    }
}
