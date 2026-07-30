package com.gme.pay.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Proves the T3-11 defect-2 fix: that a slow job can no longer starve a sibling, and that when
 * something does hold the pool the lag metric <b>moves</b> rather than staying silent.
 *
 * <p>The starvation half is tested with a real Spring context, a real one-thread and a real
 * two-thread scheduler pool, and two real {@code @Scheduled} jobs — one that blocks and one that must
 * still run. Asserting on the configuration property alone would prove the property was set, not that
 * setting it fixes anything.
 */
class SchedulerLagProbeTest {

    // -----------------------------------------------------------------------------------------
    // The metric moves
    // -----------------------------------------------------------------------------------------

    @Test
    void lagIsZeroWhenHeartbeatsArriveOnTime() {
        MeterRegistry registry = new SimpleMeterRegistry();
        SchedulerLagProbe probe = new SchedulerLagProbe(
                registry, emptyProvider(), Duration.ofMillis(1000));
        probe.configureTasks(new ScheduledTaskRegistrar());

        probe.heartbeat();   // establishes the baseline; contributes no measurement by design
        probe.heartbeat();   // n=1, due at baseline+1000ms, running immediately => early, so 0

        assertThat(registry.get(SchedulerLagProbe.LAG_GAUGE).gauge().value()).isZero();
    }

    @Test
    void lagRisesWhenAHeartbeatIsLateAndTheHighWaterMarkRemembersIt() throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        SchedulerLagProbe probe = new SchedulerLagProbe(
                registry, emptyProvider(), Duration.ofMillis(50));
        probe.configureTasks(new ScheduledTaskRegistrar());

        probe.heartbeat();                 // baseline
        Thread.sleep(200);                 // the pool was busy: this heartbeat could not run
        probe.heartbeat();                 // n=1, due 50ms after baseline, so ~150ms late

        double lag = registry.get(SchedulerLagProbe.LAG_GAUGE).gauge().value();
        assertThat(lag)
                .as("a heartbeat blocked for 200ms must report roughly that much lag")
                .isGreaterThan(0.05);

        double peak = registry.get(SchedulerLagProbe.LAG_MAX_GAUGE).gauge().value();
        assertThat(peak).isGreaterThanOrEqualTo(lag);

        // Recovery drains one period per tick, which is what a fixed-rate scheduler really does after
        // a stall: it fires the missed ticks back to back until it has caught up. Five ticks at 50ms
        // more than covers a 200ms debt.
        for (int i = 0; i < 5; i++) {
            probe.heartbeat();
        }
        // The instantaneous gauge recovers...
        assertThat(registry.get(SchedulerLagProbe.LAG_GAUGE).gauge().value()).isLessThan(lag);
        // ...but the peak must NOT be erased. A starvation episode shorter than a scrape interval is
        // invisible to the instantaneous gauge, and that is exactly the episode an operator needs.
        assertThat(registry.get(SchedulerLagProbe.LAG_MAX_GAUGE).gauge().value()).isEqualTo(peak);
    }

    @Test
    void poolGaugesReadTheRealExecutorAndAreNaNRatherThanZeroWhenThereIsNothingToRead() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.initialize();
        try {
            SchedulerLagProbe probe = new SchedulerLagProbe(
                    registry, providerOf(scheduler), Duration.ofMillis(1000));
            probe.configureTasks(new ScheduledTaskRegistrar());

            // "pool size 1" was a default nobody noticed. Putting it on a dashboard is the point.
            assertThat(registry.get(SchedulerLagProbe.POOL_SIZE_GAUGE).gauge().value()).isEqualTo(4d);
            assertThat(registry.get(SchedulerLagProbe.QUEUED_GAUGE).gauge().value()).isZero();
        } finally {
            scheduler.shutdown();
        }

        MeterRegistry other = new SimpleMeterRegistry();
        SchedulerLagProbe blind = new SchedulerLagProbe(other, emptyProvider(), Duration.ofMillis(1000));
        blind.configureTasks(new ScheduledTaskRegistrar());
        // NaN, not 0. "I cannot see the pool" and "the pool is empty" are different facts, and a
        // reassuring zero for the first is how a monitoring gap gets mistaken for health.
        assertThat(other.get(SchedulerLagProbe.POOL_SIZE_GAUGE).gauge().value()).isNaN();
    }

    @Test
    void aFailingHeartbeatCannotBreakTheSchedulerItMeasures() {
        // The probe rides on the same pool as real money-path jobs, so it must be incapable of
        // throwing into it. Driving it with no registered gauges and no baseline exercises the
        // guarded path.
        SchedulerLagProbe probe = new SchedulerLagProbe(
                new SimpleMeterRegistry(), emptyProvider(), Duration.ofMillis(1));
        for (int i = 0; i < 5; i++) {
            probe.heartbeat();   // must not throw
        }
    }

    // -----------------------------------------------------------------------------------------
    // A slow job can no longer starve a sibling
    // -----------------------------------------------------------------------------------------

    /**
     * The defect, reproduced. One thread, one job that blocks (standing in for the 1-second outbox
     * poller under load) and one job that must keep firing (standing in for the T3-3 stuck-transaction
     * alerter). With {@code pool.size=1} the second job never runs at all.
     */
    @Test
    void withPoolSizeOneASlowJobStarvesItsSibling() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(TwoJobs.class)
                .withPropertyValues("spring.task.scheduling.pool.size=1")
                .run(context -> {
                    TwoJobs jobs = context.getBean(TwoJobs.class);
                    assertThat(jobs.blockerStarted.await(5, TimeUnit.SECONDS)).isTrue();
                    // The sibling is scheduled every 20ms; give it far more time than it needs.
                    boolean ranAnyway = jobs.siblingRan.await(1, TimeUnit.SECONDS);
                    assertThat(ranAnyway)
                            .as("this is the T3-11 defect: on one thread the sibling never gets to run")
                            .isFalse();
                    jobs.release.countDown();
                });
    }

    /** The fix. Same jobs, a pool sized to the job count, and the sibling keeps firing. */
    @Test
    void withPoolSizedToTheJobCountTheSiblingKeepsFiring() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(TwoJobs.class)
                .withPropertyValues("spring.task.scheduling.pool.size=2")
                .run(context -> {
                    TwoJobs jobs = context.getBean(TwoJobs.class);
                    assertThat(jobs.blockerStarted.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(jobs.siblingRan.await(5, TimeUnit.SECONDS))
                            .as("with a thread of its own the sibling must run while the blocker holds one")
                            .isTrue();
                    assertThat(jobs.siblingRuns.get()).isPositive();
                    jobs.release.countDown();
                });
    }

    @Configuration
    @EnableScheduling
    static class TwoJobs {

        final CountDownLatch blockerStarted = new CountDownLatch(1);
        final CountDownLatch siblingRan = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger siblingRuns = new AtomicInteger();

        /** Stands in for the 1-second outbox poller whose tick has grown past its interval. */
        @Scheduled(initialDelay = 0, fixedDelay = 10_000)
        public void blocker() throws InterruptedException {
            blockerStarted.countDown();
            release.await(10, TimeUnit.SECONDS);
        }

        /** Stands in for a T3-3 safety-net sweeper: short, frequent, and the one that must not stop. */
        @Scheduled(initialDelay = 20, fixedDelay = 20)
        public void sibling() {
            siblingRuns.incrementAndGet();
            siblingRan.countDown();
        }
    }

    // -----------------------------------------------------------------------------------------
    // Wiring
    // -----------------------------------------------------------------------------------------

    @Test
    void autoConfigurationRegistersTheProbeWhenAMeterRegistryIsPresent() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SchedulerLagAutoConfiguration.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> assertThat(context).hasSingleBean(SchedulerLagProbe.class));
    }

    @Test
    void autoConfigurationIsInertWithoutAMeterRegistry() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SchedulerLagAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(SchedulerLagProbe.class));
    }

    // -----------------------------------------------------------------------------------------

    private static ObjectProvider<TaskScheduler> emptyProvider() {
        return new StubProvider(null);
    }

    private static ObjectProvider<TaskScheduler> providerOf(TaskScheduler scheduler) {
        return new StubProvider(scheduler);
    }

    /** Minimal {@link ObjectProvider}: only {@code getIfAvailable()} is exercised by the probe. */
    private record StubProvider(TaskScheduler scheduler) implements ObjectProvider<TaskScheduler> {

        @Override
        public TaskScheduler getIfAvailable() {
            return scheduler;
        }

        @Override
        public TaskScheduler getObject() {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskScheduler getObject(Object... args) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskScheduler getIfUnique() {
            return scheduler;
        }
    }
}
