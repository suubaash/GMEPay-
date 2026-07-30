package com.gme.pay.scheme.zeropay.config;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Distributed scheduler locking for scheme-adapter-zeropay (gap <b>T3-11</b> defect 3).
 *
 * <h2>Why an adapter needs this</h2>
 *
 * <p>{@code ZeroPayBatchScheduler}'s six KST windows do not compute something and store it — each one
 * generates a ZP00xx file and <b>transfers it to ZeroPay</b>. On two replicas both pods fire the same
 * cron in the same second and the scheme receives the file twice. A duplicate ZP0011 is a duplicate
 * payment-result registration; a duplicate ZP0061 is a duplicate settlement request. Neither is
 * recallable, and the {@code zp_batch_files} registry would hold two GENERATED rows for one business
 * date, leaving even the audit trail unable to say what was actually sent.
 *
 * <p>Same shape as prefunding's {@code OutboxConfig} and transaction-mgmt's {@code ShedLockConfig}:
 * one provider, one table, {@code usingDbTime()}.
 *
 * <h2>usingDbTime()</h2>
 *
 * <p>Expiry is judged by the <b>database</b> clock. Cron work is where wall-clock skew bites hardest:
 * two pods a few seconds apart would disagree about whether the 05:00 lock had lapsed, and the fast
 * one would begin a second generate-and-transfer while the first was still writing.
 *
 * <p>{@code defaultLockAtMostFor} is the crash safety net for any future job that does not name its
 * own. All six windows name theirs.
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
