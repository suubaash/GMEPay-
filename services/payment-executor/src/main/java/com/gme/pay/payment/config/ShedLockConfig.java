package com.gme.pay.payment.config;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Distributed scheduler locking for payment-executor (gap <b>T3-11</b> defect 3).
 *
 * <h2>What was unlocked here</h2>
 *
 * <p>Five {@code @Scheduled} jobs, two of them consequential on a second replica:
 *
 * <ul>
 *   <li>{@code AuthorizationExpirySweeper} <b>releases prefunding holds</b> on expired
 *       authorizations. Two replicas selecting the same expired rows both issue a release, so a
 *       partner's float can be credited twice for one authorization.</li>
 *   <li>{@code RevenuePostingReplayScheduler} drains {@code revenue_posting_failures} against a
 *       <b>bounded</b> attempt budget. Its "exactly one attempt per row per sweep" property is
 *       enforced per JVM, so N replicas spend that budget N times faster and can burn a row to POISON
 *       over what was really a single downstream outage. The money itself is protected by the
 *       201-vs-200 check the replay already does; the lock protects the retry budget.</li>
 * </ul>
 *
 * <p>{@code FxExposureScheduler} and {@code OpsAlertRetentionSweeper} are locked too. Pruning is
 * idempotent and would survive being run twice, but leaving one job unlocked makes "is this one
 * locked?" a judgement someone has to re-make each time a job body changes — and the job most likely
 * to change is the one whose new behaviour is no longer idempotent.
 *
 * <p>Same shape as prefunding's {@code OutboxConfig} and transaction-mgmt's {@code ShedLockConfig}:
 * one provider, one table, {@code usingDbTime()} so lock expiry follows the database clock rather
 * than each pod's.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class ShedLockConfig {

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
