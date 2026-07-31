package com.gme.pay.ledger.config;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Distributed scheduler locking for revenue-ledger (gap <b>T3-11</b> defect 3, the named follow-up).
 *
 * <h2>Why this one was last, and why that mattered</h2>
 *
 * <p>Four services were locked in the T3-11 pass and two already were; revenue-ledger was skipped
 * because another agent held the module. It was therefore the <b>only</b> service in the fleet whose
 * {@code @Scheduled} work still ran unguarded on every instance, and the report named it as "the last
 * thing keeping revenue-ledger at one replica".
 *
 * <p>The job is {@code OutboxPublisher#publishPending} on a 1 s tick. Its failure mode at N&gt;1 is not
 * a duplicate log line: the tick reads unpublished rows and hands each to the {@code EventPublisher}
 * (Kafka in a real deployment), stamping {@code publishedAt} only after the publish returns. Two
 * replicas ticking a second apart both select the same rows and both publish them, so every downstream
 * consumer of a ledger event sees it N times. The consumers are documented as idempotent — that is the
 * at-least-once half of the outbox contract — but "at least once" is a bound on redelivery of the same
 * event, not a licence to multiply publishes by the replica count, and revenue events are what
 * reporting and reconciliation aggregate.
 *
 * <p>Deliberately identical in shape to settlement-reconciliation's and payment-executor's config:
 * same provider, same {@code usingDbTime()}, same table shape. Three subtly different lock
 * implementations across one fleet is how one of them ends up wrong.
 *
 * <h2>usingDbTime()</h2>
 *
 * <p>Lock expiry is evaluated against the <b>database</b> clock, not each pod's. Two pods whose
 * wall clocks differ by seconds would otherwise disagree about whether a lock had lapsed, and the fast
 * one would start a second drain while the first was still publishing.
 *
 * <p>{@code defaultLockAtMostFor} is the crash safety net — a holder that dies mid-tick releases
 * nothing, so a lock with no expiry would stop the outbox permanently. The one job here names its own
 * value.
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
