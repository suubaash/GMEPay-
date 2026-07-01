package com.gme.pay.prefunding.outbox;

import com.gme.pay.events.EventPublisher;
import com.gme.pay.events.LogEventPublisher;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring wiring for the Outbox pattern in prefunding (mirrors revenue-ledger's OutboxConfig).
 *
 * <ul>
 *   <li>{@link EnableScheduling} turns on {@code @Scheduled} so
 *       {@link OutboxPublisher#publishPending()} fires on its configured fixed delay
 *       ({@code gmepay.outbox.poll-ms}, default 1 s).</li>
 *   <li>{@link EnableSchedulerLock} + a {@link JdbcTemplateLockProvider} (backed by the
 *       {@code shedlock} table, V008) give the drain a DISTRIBUTED lock (#3): with more than
 *       one replica, exactly one instance runs each tick. {@code defaultLockAtMostFor} is the
 *       safety net if a holder dies mid-tick.</li>
 *   <li>A default {@link EventPublisher} bean ({@link LogEventPublisher}) is registered
 *       only if none is already present, so tests can swap in a
 *       {@code RecordingEventPublisher} and integration wiring can swap in the Kafka-backed
 *       publisher (lib-events-kafka) without touching this config.</li>
 * </ul>
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30S")
public class OutboxConfig {

    @Bean
    @ConditionalOnMissingBean
    public EventPublisher logEventPublisher() {
        return new LogEventPublisher();
    }

    /** ShedLock lock store: the {@code shedlock} table (V008), via JdbcTemplate on the app DataSource. */
    @Bean
    @ConditionalOnMissingBean
    public LockProvider shedLockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime() // compare against DB clock, not per-instance wall clock
                        .build());
    }
}
