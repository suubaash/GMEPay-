package com.gme.pay.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.annotation.SchedulingConfigurer;

/**
 * Measures whether the {@code @Scheduled} pool is keeping up — the signal
 * {@code RUNBOOK_LOAD_AND_CAPACITY.md} §4.1 #5 says does not exist, which is why the starvation it
 * predicts is currently invisible.
 *
 * <h2>What is actually being measured, and why a heartbeat</h2>
 *
 * <p>The failure mode is specific: Spring's scheduler pool defaults to <b>one</b> thread, and
 * transaction-mgmt stacks a 1-second outbox poller, a 10-second expiry sweeper and a 60-second
 * stuck-transaction alerter on it. When the outbox poller's tick starts taking longer than a second
 * — which is exactly what load does — the two sweepers do not run late by a little. They queue
 * behind it, and the T3-3 safety nets stop firing at the moment volume makes them matter. Nothing
 * logs this. Nothing alerts on it. Each job, examined alone, looks fine.
 *
 * <p>Measuring per-job overrun would need each job instrumented and would still miss the interesting
 * case (a job that never got a thread produces no measurement at all). Instead this registers <b>one
 * more fixed-rate task on the same pool</b> and records how late it starts. A task that should run
 * every second and runs 40 seconds late is direct evidence that a sibling is holding the only
 * thread, and it is evidence produced by the same queue the real jobs sit in. Cheap, too: the task
 * body is two arithmetic operations.
 *
 * <h2>Meters</h2>
 *
 * <table>
 *   <caption>Registered meters</caption>
 *   <tr><th>Meter</th><th>Meaning</th></tr>
 *   <tr><td>{@code gmepay.scheduler.lag} (seconds)</td>
 *       <td>How late the most recent heartbeat started, versus when it was due. Near zero on a
 *           healthy pool. <b>This is the one to alert on.</b></td></tr>
 *   <tr><td>{@code gmepay.scheduler.lag.max} (seconds)</td>
 *       <td>High-water mark since start. A scrape interval is 15–60 s; a starvation episode shorter
 *           than that is invisible to the instantaneous gauge and visible here.</td></tr>
 *   <tr><td>{@code gmepay.scheduler.pool.size}</td>
 *       <td>Threads in the pool. Makes "pool size 1" a fact on a dashboard rather than a default
 *           nobody noticed.</td></tr>
 *   <tr><td>{@code gmepay.scheduler.active}</td>
 *       <td>Threads currently running a task. Equal to pool size means saturated.</td></tr>
 *   <tr><td>{@code gmepay.scheduler.queued}</td>
 *       <td>Tasks waiting for a thread — the backlog itself.</td></tr>
 * </table>
 *
 * <p>Lag and saturation together, because either alone misleads: a saturated pool whose jobs are all
 * short is healthy, and a pool with one idle thread can still be starving a job that a long-running
 * sibling is blocking.
 *
 * <h2>Fail-safe</h2>
 *
 * <p>This runs on the startup path of twenty services. Every read is guarded and every gauge returns
 * {@code NaN} rather than throwing if the scheduler is not a {@link ThreadPoolTaskScheduler} (a
 * service may supply its own {@link TaskScheduler}). A metrics probe must not be able to stop a
 * service booting, and it must not be able to break a scheduled job — so the heartbeat body catches
 * everything.
 */
public class SchedulerLagProbe implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLagProbe.class);

    static final String LAG_GAUGE = "gmepay.scheduler.lag";
    static final String LAG_MAX_GAUGE = "gmepay.scheduler.lag.max";
    static final String POOL_SIZE_GAUGE = "gmepay.scheduler.pool.size";
    static final String ACTIVE_GAUGE = "gmepay.scheduler.active";
    static final String QUEUED_GAUGE = "gmepay.scheduler.queued";

    private final MeterRegistry meterRegistry;
    private final ObjectProvider<TaskScheduler> schedulerProvider;
    private final Duration heartbeatPeriod;

    /** Nanos of the monotonic clock at which the first heartbeat ran; the baseline every due-time derives from. */
    private final AtomicLong baselineNanos = new AtomicLong(Long.MIN_VALUE);
    /** Heartbeats observed so far, so the n-th one's due time is baseline + n*period without wall-clock drift. */
    private final AtomicLong ticks = new AtomicLong();
    /** Last observed lateness, in nanos. */
    private final AtomicLong lastLagNanos = new AtomicLong();
    /** High-water lateness, in nanos. */
    private final AtomicLong maxLagNanos = new AtomicLong();

    public SchedulerLagProbe(MeterRegistry meterRegistry,
                             ObjectProvider<TaskScheduler> schedulerProvider,
                             Duration heartbeatPeriod) {
        this.meterRegistry = meterRegistry;
        this.schedulerProvider = schedulerProvider;
        this.heartbeatPeriod = heartbeatPeriod;
    }

    /**
     * Registers the heartbeat on the very registrar that carries every other {@code @Scheduled}
     * method in this service, which is what makes it share their pool and therefore their fate.
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedRateTask(this::heartbeat, heartbeatPeriod);
        registerGauges();
        log.info("scheduler lag probe registered (heartbeat every {}ms, gauges {} / {})",
                heartbeatPeriod.toMillis(), LAG_GAUGE, QUEUED_GAUGE);
    }

    /**
     * Records lateness against a monotonic baseline.
     *
     * <p>{@code System.nanoTime}, not the wall clock: an NTP step or a container clock adjustment
     * would otherwise register as a scheduler stall, and the whole value of this metric is that a
     * non-zero reading means something real.
     */
    void heartbeat() {
        try {
            long now = System.nanoTime();
            if (baselineNanos.compareAndSet(Long.MIN_VALUE, now)) {
                // First run establishes the baseline. Its own lateness (context start-up, first-tick
                // scheduling) is not scheduler starvation and must not be reported as such.
                return;
            }
            long n = ticks.incrementAndGet();
            long dueAt = baselineNanos.get() + n * heartbeatPeriod.toNanos();
            long lag = Math.max(0L, now - dueAt);
            lastLagNanos.set(lag);
            maxLagNanos.accumulateAndGet(lag, Math::max);
        } catch (RuntimeException e) {
            // A metric must never break the pool it is measuring.
            log.debug("scheduler lag heartbeat failed: {}", e.toString());
        }
    }

    private void registerGauges() {
        Gauge.builder(LAG_GAUGE, this, self -> nanosToSeconds(self.lastLagNanos.get()))
                .description("How late the most recent scheduler heartbeat started versus when it was due")
                .baseUnit("seconds")
                .strongReference(true)
                .register(meterRegistry);
        Gauge.builder(LAG_MAX_GAUGE, this, self -> nanosToSeconds(self.maxLagNanos.get()))
                .description("High-water scheduler heartbeat lateness since start")
                .baseUnit("seconds")
                .strongReference(true)
                .register(meterRegistry);
        // getCorePoolSize(), not getPoolSize(): a ScheduledThreadPoolExecutor creates its threads
        // lazily, so getPoolSize() reports 0 on an idle service and would make a correctly-sized pool
        // look like no pool at all. The configured size is the fact worth publishing — it is what
        // turns "pool size 1" from an unnoticed default into something visible on a dashboard.
        Gauge.builder(POOL_SIZE_GAUGE, this, self -> self.pool(ScheduledThreadPoolExecutor::getCorePoolSize))
                .description("Configured thread count of the @Scheduled task pool")
                .baseUnit("threads")
                .strongReference(true)
                .register(meterRegistry);
        Gauge.builder(ACTIVE_GAUGE, this, self -> self.pool(ScheduledThreadPoolExecutor::getActiveCount))
                .description("Scheduler threads currently executing a task")
                .baseUnit("threads")
                .strongReference(true)
                .register(meterRegistry);
        Gauge.builder(QUEUED_GAUGE, this, self -> self.pool(e -> e.getQueue().size()))
                .description("Tasks waiting for a scheduler thread")
                .baseUnit("tasks")
                .strongReference(true)
                .register(meterRegistry);
    }

    private static double nanosToSeconds(long nanos) {
        return nanos / 1_000_000_000d;
    }

    /**
     * Reads a value off the underlying executor, or {@code NaN} when there is nothing to read.
     *
     * <p>Resolved lazily on every scrape rather than captured once: the {@code TaskScheduler} bean
     * does not necessarily exist yet when {@code configureTasks} runs, and a service is free to
     * supply a {@link TaskScheduler} that is not a {@link ThreadPoolTaskScheduler} at all. NaN is the
     * honest answer to "how saturated is a pool I cannot see", and it renders as absent rather than
     * as a reassuring zero.
     */
    private double pool(java.util.function.ToIntFunction<ScheduledThreadPoolExecutor> read) {
        try {
            TaskScheduler scheduler = schedulerProvider.getIfAvailable();
            if (scheduler instanceof ThreadPoolTaskScheduler tpts) {
                return read.applyAsInt(tpts.getScheduledThreadPoolExecutor());
            }
            return Double.NaN;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
