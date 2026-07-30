package com.gme.pay.bff.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

/**
 * Scheduling + distributed scheduler locking for ops-partner-bff.
 *
 * <p>Scheduling used to be enabled only when paging escalation was switched on
 * ({@code OpsPagingSchedulingConfig}), because escalation was the service's only scheduled job. The
 * durable {@code ops_alerts} table (V001) adds a second one — the retention sweeper — which must run
 * whether or not escalation is armed, otherwise "durable" quietly means "unbounded". Two
 * {@code @EnableScheduling} configurations in one context are harmless (Spring registers a single
 * {@code ScheduledAnnotationBeanPostProcessor}), and the escalation-gated one is kept so its
 * conditional documentation stays where the escalation scheduler is.
 *
 * <h2>Which jobs are locked</h2>
 * <ul>
 *   <li>{@code OpsAlertRetentionSweeper} — <b>locked</b>. Its bulk {@code DELETE} is idempotent, but
 *       per T3-11 the idempotent jobs are not treated as exceptions: N replicas issuing the same bulk
 *       delete is N times the contention for no benefit, and whether a job is locked must not be a
 *       judgement re-made every time its body changes.</li>
 *   <li>{@code OpsPagingEscalationScheduler} — <b>deliberately NOT locked</b>. A lock can only
 *       subtract escalations: if the lock row sticks or the provider is unavailable, <em>no</em>
 *       replica sweeps and an un-acked CRITICAL alert stops escalating — a missed page during an
 *       incident. The duplicate a lock would prevent is already prevented at the pager by the shared
 *       {@code PagingCooldown}, claimed atomically before each page. This class provides the
 *       {@link LockProvider} that would make locking that sweep possible; not using it there is the
 *       decision, recorded in that class and in V003.</li>
 * </ul>
 *
 * <p>Same shape as payment-executor's and transaction-mgmt's {@code ShedLockConfig}: one provider, one
 * table, {@code usingDbTime()} so lock expiry follows the database clock rather than each pod's.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class OpsSchedulingConfig {

    /**
     * {@code @ConditionalOnMissingBean} so a test slice can substitute an in-memory provider without
     * excluding this configuration — excluding it would also drop {@code @EnableSchedulerLock}, which
     * disables locking silently rather than replacing it.
     */
    @Bean
    @ConditionalOnMissingBean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
