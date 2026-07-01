package com.gme.pay.txn.config;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Distributed scheduler locking (#3). {@link EnableSchedulerLock} activates ShedLock's AOP so any
 * {@code @Scheduled} method also annotated {@code @SchedulerLock} acquires a named lock in the
 * {@code shedlock} table (V010) before running — so across horizontally-scaled replicas the sweepers
 * and the outbox drain fire on at most one node per tick, never double-firing.
 *
 * <p>The {@link JdbcTemplateLockProvider} reuses the service's existing {@link DataSource} (no extra
 * infra). {@code usingDbTime()} makes the lock window use the DATABASE clock, so replicas with skewed
 * system clocks still agree on lock expiry.
 *
 * <p>{@code defaultLockAtMostFor} is the safety-net TTL: if a node dies mid-run without releasing,
 * the lock auto-expires after this so another node can take over. Per-method {@code @SchedulerLock}
 * values override it.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
