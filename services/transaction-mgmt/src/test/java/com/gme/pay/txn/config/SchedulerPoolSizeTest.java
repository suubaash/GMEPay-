package com.gme.pay.txn.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.txn.outbox.OutboxPublisher;
import com.gme.pay.txn.service.ExpirySweeperService;
import com.gme.pay.txn.service.StuckTransactionAlertSweeper;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * T3-11 defect 2, on the service the runbook named: <b>the 1-second outbox poller can no longer
 * starve the T3-3 safety nets.</b>
 *
 * <p>{@code RUNBOOK_LOAD_AND_CAPACITY.md} §4.1 #5: {@code spring.task.scheduling.pool.size} was set in
 * no file in this repository, so Spring's default of one thread applied. transaction-mgmt stacks a
 * 1-second outbox poller, a 10-second expiry sweeper and a 60-second stuck-transaction alerter on
 * that single thread. Under load the outbox tick lengthens and the two sweepers simply stop running —
 * the safety nets T3-3 armed go quiet at precisely the moment volume makes them matter.
 *
 * <p>This test pins the relationship (pool size covers the job count), not just the number, so adding
 * a fourth scheduled job to this service fails here rather than silently reintroducing contention.
 * The behavioural proof that a sized pool prevents starvation lives in lib-errors'
 * {@code SchedulerLagProbeTest}, which runs two real jobs on a real one-thread and two-thread pool.
 */
class SchedulerPoolSizeTest {

    @Test
    @DisplayName("the shipped pool is at least as large as the number of scheduled jobs")
    void poolIsSizedToTheJobCount() throws IOException {
        Properties shipped = PropertiesLoaderUtils.loadProperties(
                new ClassPathResource("application.properties"));

        String configured = shipped.getProperty("spring.task.scheduling.pool.size");
        assertThat(configured)
                .as("unset means Spring's default of ONE thread for every job in this service — the "
                        + "T3-11 defect itself")
                .isNotNull();

        int poolSize = Integer.parseInt(configured.trim());
        int jobs = scheduledMethodCount();

        assertThat(poolSize)
                .as("%d scheduled jobs need at least %d threads, or one can starve another", jobs, jobs)
                .isGreaterThanOrEqualTo(jobs);
        // Plus the lib-errors scheduler-lag heartbeat, which rides on this same pool: a probe that
        // cannot get a thread reports no lag, which is the most misleading possible reading.
        assertThat(poolSize)
                .as("leave room for the scheduler-lag heartbeat that shares this pool")
                .isGreaterThan(jobs);
    }

    private static int scheduledMethodCount() {
        List<Method> scheduled = new ArrayList<>();
        for (Class<?> type : List.of(
                OutboxPublisher.class, ExpirySweeperService.class, StuckTransactionAlertSweeper.class)) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getAnnotation(Scheduled.class) != null) {
                    scheduled.add(method);
                }
            }
        }
        return scheduled.size();
    }
}
