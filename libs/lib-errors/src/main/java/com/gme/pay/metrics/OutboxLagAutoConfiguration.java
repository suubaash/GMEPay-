package com.gme.pay.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * Registers <b>outbox backlog and lag gauges</b> on every service that has an outbox table
 * (gap <b>T3-5</b>: no queue-depth signal anywhere).
 *
 * <h2>Why it lives here, and why it is automatic</h2>
 *
 * <p>Four services run the transactional-outbox pattern — transaction-mgmt, revenue-ledger,
 * prefunding and settlement-reconciliation — and all four declare the <em>same</em> table shape
 * ({@code id, aggregate_id, event_type, payload, created_at, published_at}), because they were
 * built from the same template. The drain is a {@code @Scheduled} publisher, and per
 * {@code RUNBOOK_LOAD_AND_CAPACITY.md} §4.1 #5 those publishers share a single-thread scheduler
 * pool with several other jobs. That combination — a queue with one drain thread contending with
 * other work — is precisely the thing that degrades silently under load, and until now nothing
 * measured it.
 *
 * <p>Riding along in lib-errors means all four get it with no per-service edit and no
 * {@code settings.gradle} change, exactly as the trace, RBAC and correlation auto-configurations
 * already do. A service <em>without</em> an outbox table registers nothing at all.
 *
 * <h2>Fail-safe by construction</h2>
 *
 * <p>This is on the startup path of twenty services, so it is written to be incapable of breaking
 * one. It activates only when both a {@link MeterRegistry} and a {@link DataSource} bean exist; it
 * probes for the table once, inside a catch-all; a probe failure or a missing table means no
 * gauges rather than an exception; and reads are cached behind a TTL so a scrape storm cannot turn
 * into database load. {@code gmepay.metrics.outbox.enabled=false} switches it off entirely.
 *
 * @see OutboxLagGauges for the query, the caching, and what each gauge means
 */
@AutoConfiguration
// @ConditionalOnBean is evaluated in auto-configuration order, so without this the condition can
// run BEFORE Boot has contributed the DataSource and the meter registry and silently decide
// neither exists — the failure mode being a gauge that is simply never there, with no error.
// Referenced by NAME so lib-errors needs no compile dependency on actuator-autoconfigure.
@org.springframework.boot.autoconfigure.AutoConfigureAfter(name = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration"
})
@ConditionalOnClass({MeterRegistry.class, DataSource.class})
@ConditionalOnBean({MeterRegistry.class, DataSource.class})
@ConditionalOnProperty(name = "gmepay.metrics.outbox.enabled", havingValue = "true",
        matchIfMissing = true)
public class OutboxLagAutoConfiguration {

    /**
     * {@code @Lazy(false)} is load-bearing, not decoration.
     *
     * <p>This bean has no dependents — its whole job is a side effect performed in
     * {@code @PostConstruct}. Under {@code spring.main.lazy-initialization=true} (which the E2E
     * fleet sets, and which some deployments use to cut start-up time) a bean nobody injects is
     * never instantiated, so the gauges would never register and the absence would be completely
     * silent. {@code @Lazy(false)} is Boot's documented opt-out from global lazy initialisation.
     */
    @Bean
    @org.springframework.context.annotation.Lazy(false)
    public OutboxLagGauges gmepayOutboxLagGauges(MeterRegistry meterRegistry, DataSource dataSource) {
        return new OutboxLagGauges(meterRegistry, dataSource);
    }
}
