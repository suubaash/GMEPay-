package com.gme.pay.registry.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.audit.AuditPublisher;
import com.gme.pay.audit.AuditPublisherAutoConfiguration;
import com.gme.pay.audit.DbAuditPublisher;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * Regression guard for the double-write this service shipped with, found while closing gap T5-1.
 *
 * <h2>What went wrong</h2>
 *
 * <p>{@link AuditLogService} writes the audit row itself via JPA and then hands the sealed event to
 * an injected {@link AuditPublisher} for the tier-2 fan-out. {@link AuditConfig} used to declare
 * {@code LogAuditPublisher} under {@code @ConditionalOnMissingBean(AuditPublisher.class)} — a
 * condition evaluated while user config is registered, i.e. <b>before</b> auto-configuration — so
 * {@link AuditPublisherAutoConfiguration} then additionally registered {@link DbAuditPublisher} as
 * {@code @Primary}, and the {@code @Primary} bean won the injection point.
 *
 * <p>{@code DbAuditPublisher.publish} INSERTs into {@code audit_log}. Every audited write therefore
 * produced <b>two</b> rows sharing one {@code prev_hash} and one {@code row_hash} — and a chain with
 * two siblings at the same link cannot verify. The tamper-evidence mechanism would have declared
 * the platform's own audit log tampered, on every aggregate, in a way no investigator could
 * distinguish from an attack. No test caught it because the audit slice tests all install a
 * {@code @Primary} recording publisher and never load auto-configurations.
 *
 * <p>This test loads {@link AuditConfig} together with the real auto-configuration and a real
 * DataSource — the production shape — and asserts the fan-out publisher is never a DB publisher.
 */
class AuditFanoutRoutingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuditPublisherAutoConfiguration.class))
            .withUserConfiguration(AuditConfig.class, DataSourceConfig.class);

    @Test
    @DisplayName("the injected fan-out publisher is NOT a DbAuditPublisher, even though one exists")
    void fanoutPublisherIsNeverTheDbPublisher() {
        runner.run(context -> {
            // The auto-configuration still registers the DB publisher — other services legitimately
            // use it as their tier-1 writer, so it must not be suppressed globally.
            assertThat(context).hasSingleBean(DbAuditPublisher.class);

            // ...but the bean this service injects for FAN-OUT must not be it, or every audit row
            // is written twice and no chain verifies.
            AuditPublisher injected = context.getBean(AuditPublisher.class);
            assertThat(injected)
                    .as("a DbAuditPublisher here means every audit row is INSERTed twice")
                    .isNotInstanceOf(DbAuditPublisher.class);
        });
    }

    @Test
    @DisplayName("with no Kafka configured the fan-out falls back to the log publisher")
    void fallsBackToLogPublisherWithoutKafka() {
        runner.run(context -> assertThat(context.getBean(AuditPublisher.class).getClass().getName())
                .endsWith("LogAuditPublisher"));
    }

    @Configuration(proxyBeanMethods = false)
    static class DataSourceConfig {
        /**
         * A real DataSource, because that is exactly the condition
         * ({@code @ConditionalOnBean(DataSource.class)}) that used to pull the DB publisher into the
         * injection point. Without one the bug is not reproducible.
         */
        @Bean
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .generateUniqueName(true)
                    .build();
        }
    }
}
