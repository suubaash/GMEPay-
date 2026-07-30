package com.gme.pay.registry.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.audit.AuditPublisher;
import com.gme.pay.audit.AuditPublisherAutoConfiguration;
import com.gme.pay.audit.DbAuditPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
 * <p>{@code DbAuditPublisher.publish} INSERTs into {@code audit_log}. Every audited write would
 * therefore produce <b>two</b> rows sharing one {@code prev_hash} and one {@code row_hash} — and a
 * chain with two siblings at the same link cannot verify. The tamper-evidence mechanism would
 * declare the platform's own audit log tampered, on every aggregate, in a way no investigator could
 * distinguish from an attack. No test caught it because the audit slice tests all install a
 * {@code @Primary} recording publisher and never load auto-configurations.
 *
 * <p>It was <b>latent</b> only because of a second bug: lib-audit's auto-configuration had no
 * {@code @AutoConfigureAfter(DataSourceAutoConfiguration.class)}, so the DB publisher was never
 * actually created in a real application. Fixing that ordering — required, or auth-identity and
 * prefunding get log lines instead of hash-chained rows — is precisely what would have activated
 * the double-write here. Both fixes land together, which is why this test asserts the two beans
 * coexisting rather than the DB publisher's absence.
 *
 * <p>This test loads {@link AuditConfig} together with the real auto-configuration and a real
 * DataSource — the production shape — and asserts the fan-out publisher is never a DB publisher.
 */
class AuditFanoutRoutingTest {

    /**
     * The DataSource comes from the REAL {@link org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration}
     * via properties, not from a user {@code @Bean}. That distinction is the whole point: a user
     * bean is registered before auto-configuration, which would make the DB publisher's
     * {@code @ConditionalOnBean(DataSource.class)} pass for a reason production does not share —
     * the trap that hid lib-audit's ordering bug (see
     * {@code DbAuditPublisherAutoConfigOrderingTest}). This runner reproduces the production shape,
     * where both the DB publisher and this service's fan-out bean genuinely exist at once.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
                    AuditPublisherAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:audit_fanout_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "spring.datasource.username=sa",
                    "spring.datasource.password=")
            .withUserConfiguration(AuditConfig.class);

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

}
