package com.gme.pay.settlement.config;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Distributed scheduler locking for settlement-reconciliation (gap <b>T3-11</b> defect 3).
 *
 * <h2>The stakes here are the highest in the fleet</h2>
 *
 * <p>Seven {@code @Scheduled} jobs run in this service and none of them was locked. Three
 * ({@code SettlementGenerationScheduler}) <b>generate and transmit settlement files to a scheme</b>.
 * A second replica does not produce a duplicate log line; it produces a duplicate settlement
 * instruction, and settlement instructions move money between institutions. The other four — the
 * outbox drain, two recon runs and the corridor recon — each write durable state a downstream reads
 * as authoritative.
 *
 * <p>This is what {@code RUNBOOK_LOAD_AND_CAPACITY.md} §4.2 meant by "the real reason those services
 * cannot be fixed by adding replicas": the throughput ceilings were never the binding constraint
 * here, the absence of this lock was.
 *
 * <p>Deliberately identical in shape to prefunding's {@code OutboxConfig} and transaction-mgmt's
 * {@code ShedLockConfig}: same provider, same {@code usingDbTime()}, same table.
 *
 * <h2>usingDbTime()</h2>
 *
 * <p>Lock expiry is evaluated against the <b>database</b> clock, not each pod's. Cron jobs are
 * exactly where wall-clock skew does damage: two pods a few seconds apart would disagree about
 * whether a lock taken at 05:00:00 had lapsed, and the fast one would start a second 05:00
 * settlement run while the first was still writing its file.
 *
 * <p>{@code defaultLockAtMostFor} is the crash safety net — a holder that dies mid-run releases
 * nothing, so a lock with no expiry would stop that job permanently. Every job here names its own
 * value, sized to that job's real worst case rather than to a shared guess.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class ShedLockConfig {

    /**
     * {@code @ConditionalOnMissingBean} so a test slice can substitute an in-memory provider without
     * excluding this configuration — excluding it would also drop {@code @EnableSchedulerLock}, which
     * disables locking silently instead of replacing it.
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
