package com.gme.pay.auth.audit;

import com.gme.pay.audit.AuditPublisherAutoConfiguration;
import com.gme.pay.audit.DbAuditPublisher;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link DbAuditPublisher} explicitly rather than relying on lib-audit's
 * auto-configuration.
 *
 * <h2>Why this bean is not redundant</h2>
 *
 * <p>{@link AuditPublisherAutoConfiguration} declares its {@code DbAuditPublisher} bean
 * {@code @Primary @ConditionalOnBean(DataSource.class)}, which reads as "active whenever this
 * service has a datasource". It is not, and the reason is auto-configuration <b>ordering</b>.
 *
 * <p>Boot evaluates auto-configuration classes in a deterministic order: sorted by class name,
 * then adjusted by {@code @AutoConfigureAfter} / {@code @AutoConfigureBefore}.
 * {@code com.gme.pay.audit.AuditPublisherAutoConfiguration} sorts <i>before</i>
 * {@code org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration} ("com." &lt;
 * "org."), and it carries no {@code @AutoConfigureAfter}. So when its
 * {@code @ConditionalOnBean(DataSource.class)} is evaluated, the {@code DataSource} bean
 * definition does not exist yet — the condition is false and <b>no {@code DbAuditPublisher} is
 * ever created</b>, in this service or any other that relies on the auto-configuration alone.
 *
 * <p>This is a genuinely nasty failure mode for an audit tier, which is why it is worth the
 * paragraph: nothing fails. The context starts, {@code LogAuditPublisher} takes over as the
 * {@code @ConditionalOnMissingBean(AuditPublisher.class)} fallback, and every audit event is
 * written to a <b>log line instead of the hash-chained table</b>. A service would look audited
 * and be running with no durable, tamper-evident trail at all. It was caught here only because
 * {@link AuthAuditService} injects the concrete {@code DbAuditPublisher} (it needs
 * {@code append}, which the {@code AuditPublisher} interface does not expose), so the missing
 * bean is a startup failure rather than a silent downgrade.
 *
 * <p>Declaring the bean here makes the wiring explicit and order-independent: a
 * {@code @Configuration} class is a user bean, registered before auto-configuration conditions
 * are evaluated, so {@code AuditPublisherAutoConfiguration}'s own
 * {@code @ConditionalOnMissingBean(DbAuditPublisher.class)} backs off cleanly and nothing is
 * defined twice. {@code LogAuditPublisher} also backs off, because an {@code AuditPublisher}
 * now exists.
 *
 * <p><b>Follow-up (outside this service, not done here):</b> lib-audit should carry
 * {@code @AutoConfigureAfter(DataSourceAutoConfiguration.class)} on
 * {@code AuditPublisherAutoConfiguration}, at which point this class becomes redundant. Until
 * then, any other service that adopted lib-audit expecting the auto-configuration to fire is
 * silently on {@code LogAuditPublisher} and should be checked.
 */
@Configuration
public class AuthAuditConfig {

    /**
     * The durable, hash-chained audit publisher for this service.
     *
     * <p>{@code @ConditionalOnMissingBean} so a test slice can supply its own instance (or a
     * differently-configured datasource) without a bean-definition clash.
     */
    @Bean
    @ConditionalOnMissingBean(DbAuditPublisher.class)
    public DbAuditPublisher dbAuditPublisher(DataSource dataSource) {
        return new DbAuditPublisher(dataSource);
    }
}
