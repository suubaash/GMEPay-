package com.gme.pay.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Pins the auto-configuration ORDER that decides whether an adopting service gets a durable,
 * hash-chained audit log or a log line (gap T5-1).
 *
 * <p>{@code dbAuditPublisher} is {@code @ConditionalOnBean(DataSource.class)}. Auto-configurations
 * are ordered by class name unless told otherwise, and {@code com.gme.pay.audit.…} sorts before
 * {@code org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration} — so without
 * {@code @AutoConfigureAfter} the condition was evaluated before any DataSource bean definition
 * existed, {@link DbAuditPublisher} was never created, and {@link LogAuditPublisher} silently took
 * over. A service would look audited and write nothing to {@code audit_log}.
 *
 * <p>The reason this went unnoticed is the trap this test exists to avoid: a test that declares its
 * own {@code DataSource} {@code @Bean} registers it as <b>user configuration</b>, which is processed
 * <i>before</i> auto-configuration — so the condition passes and the test proves the opposite of
 * production. This runner therefore uses the real {@link DataSourceAutoConfiguration} and supplies
 * the datasource through <b>properties</b>, exactly as a service does.
 */
class DbAuditPublisherAutoConfigOrderingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class, AuditPublisherAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:audit_order_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "spring.datasource.username=sa",
                    "spring.datasource.password=");

    @Test
    @DisplayName("with an auto-configured DataSource the DB publisher IS created (it used not to be)")
    void dbPublisherIsCreatedWhenTheDataSourceComesFromAutoConfiguration() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DbAuditPublisher.class);
            assertThat(context.getBean(AuditPublisher.class))
                    .as("a service with a datasource must get the durable hash-chained publisher, "
                            + "not a log line")
                    .isInstanceOf(DbAuditPublisher.class);
            assertThat(context).doesNotHaveBean(LogAuditPublisher.class);
        });
    }

    @Test
    @DisplayName("with no DataSource at all the log fallback is still the correct answer")
    void logFallbackWhenThereIsNoDataSource() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AuditPublisherAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DbAuditPublisher.class);
                    assertThat(context.getBean(AuditPublisher.class))
                            .isInstanceOf(LogAuditPublisher.class);
                });
    }
}
