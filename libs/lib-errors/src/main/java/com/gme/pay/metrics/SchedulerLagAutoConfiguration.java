package com.gme.pay.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;

/**
 * Registers the {@link SchedulerLagProbe} on every service that runs {@code @Scheduled} work
 * (gap <b>T3-11</b> defect 2 — "the starvation is currently silent").
 *
 * <h2>Why this needs no per-service wiring, and why it is inert where it should be</h2>
 *
 * <p>A {@link SchedulingConfigurer} bean is only consulted by Spring's
 * {@code ScheduledAnnotationBeanPostProcessor}, which only exists when a service has
 * {@code @EnableScheduling}. So the probe attaches itself to exactly the services that have a
 * scheduler pool to starve, and contributes literally nothing — not even a bean method call — to the
 * ones that do not. That is the correct blast radius and it comes for free from the extension point
 * rather than from a condition someone has to maintain.
 *
 * <p>Rides along in lib-errors for the same reason the outbox gauges do: every service already
 * depends on it, so no {@code settings.gradle} change and no chance of a service being missed.
 *
 * @see SchedulerLagProbe for what each meter means and why lag is measured with a heartbeat
 */
@AutoConfiguration
@org.springframework.boot.autoconfigure.AutoConfigureAfter(name = {
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration",
        "org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration"
})
@ConditionalOnClass({MeterRegistry.class, SchedulingConfigurer.class})
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(name = "gmepay.metrics.scheduler.enabled", havingValue = "true",
        matchIfMissing = true)
public class SchedulerLagAutoConfiguration {

    /**
     * Default heartbeat period, in milliseconds.
     *
     * <p>One second, matching the fastest real job in the fleet (the 1 s outbox pollers). A slower
     * heartbeat would under-report: lag is only observable at heartbeat resolution, so a probe that
     * ticks every 30 s cannot distinguish a pool that is 1 s behind from one that is 29 s behind, and
     * both of those are answers an operator needs.
     */
    static final long DEFAULT_HEARTBEAT_MS = 1000L;

    /**
     * The {@code TaskScheduler} is injected as an {@link ObjectProvider}, not directly.
     *
     * <p>Directly would be a circular dependency in the making: Boot's scheduler bean and the
     * scheduling post-processor that consumes {@link SchedulingConfigurer}s are built in an order
     * this class has no business constraining. The provider defers the lookup to scrape time, by
     * which point everything exists — and if it does not, the saturation gauges read NaN and the lag
     * heartbeat still works, because lag does not need the executor.
     */
    @Bean
    public SchedulerLagProbe gmepaySchedulerLagProbe(MeterRegistry meterRegistry,
                                                     ObjectProvider<TaskScheduler> taskScheduler,
                                                     Environment environment) {
        long periodMs = environment.getProperty(
                "gmepay.metrics.scheduler.heartbeat-ms", Long.class, DEFAULT_HEARTBEAT_MS);
        return new SchedulerLagProbe(meterRegistry, taskScheduler, Duration.ofMillis(periodMs));
    }
}
