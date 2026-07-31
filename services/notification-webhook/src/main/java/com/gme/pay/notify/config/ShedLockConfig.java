package com.gme.pay.notify.config;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Distributed scheduler locking for notification-webhook (gap <b>T3-11</b> defect 3).
 *
 * <h2>Why this service in particular</h2>
 *
 * <p>An unlocked scheduled job usually costs wasted work. Here it costs correctness in a way the
 * partner sees: {@code WebhookDispatcher} selects PENDING rows, POSTs them, and only then advances
 * the row. Two replicas ticking in the same window both select the same rows and both POST them, and
 * a webhook is not a notification a partner may ignore — it is the event a partner's own ledger,
 * reconciliation or fulfilment reacts to. {@code WebhookDispatcher}'s javadoc had carried
 * "a distributed lock (e.g. ShedLock) is a follow-up" since it was written; this is that follow-up,
 * and until it existed this service was capped at one replica for correctness, not for throughput.
 *
 * <p>Deliberately the same shape as prefunding's {@code OutboxConfig} and transaction-mgmt's
 * {@code ShedLockConfig} — same provider, same {@code usingDbTime()}, same table — because three
 * subtly different lock implementations across one fleet is how one of them ends up wrong.
 *
 * <h2>usingDbTime()</h2>
 *
 * <p>The lock window is evaluated against the <b>database</b> clock. Two pods with a few seconds of
 * clock skew would otherwise disagree about when a lock expires, and the one running fast would
 * decide an active lock had lapsed — reintroducing exactly the double-delivery this prevents, but
 * only intermittently, which is worse than never having had the lock.
 *
 * <p>{@code defaultLockAtMostFor} is the crash safety net for any job that does not name its own:
 * a holder that dies mid-run releases nothing, so without an expiry the queue would stop forever.
 * Per-method {@code @SchedulerLock(lockAtMostFor = ...)} overrides it, and the dispatcher does so
 * because its worst-case drain is longer than a generic default should be.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
public class ShedLockConfig {

    /**
     * {@code @ConditionalOnMissingBean} so a test slice can substitute an in-memory provider without
     * having to exclude this configuration (and thereby also lose {@code @EnableSchedulerLock}, which
     * would silently disable locking rather than replace it).
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
