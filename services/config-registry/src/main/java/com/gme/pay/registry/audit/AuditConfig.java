package com.gme.pay.registry.audit;

import com.gme.pay.audit.AuditPublisher;
import com.gme.pay.audit.DbAuditPublisher;
import com.gme.pay.audit.KafkaAuditPublisher;
import com.gme.pay.audit.LogAuditPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Spring wiring for this service's audit fan-out publisher.
 *
 * <h2>The bug this now prevents (found while closing gap T5-1)</h2>
 *
 * <p>{@link AuditLogService} persists the audit row <i>itself</i>, through JPA
 * ({@link AuditLogEntity}), inside the caller's transaction — that is tier 1 of ADR-007. It
 * then hands the sealed event to an injected {@link AuditPublisher} for the tier-2 fan-out.
 *
 * <p>The previous wiring declared {@code LogAuditPublisher} under
 * {@code @ConditionalOnMissingBean(AuditPublisher.class)}. That condition is evaluated while
 * user configuration is registered — <b>before</b> auto-configuration runs — so it always
 * passed, and then {@code AuditPublisherAutoConfiguration} additionally registered
 * {@link DbAuditPublisher} as {@code @Primary} (its own condition is only
 * {@code @ConditionalOnBean(DataSource.class)}, and this service has a DataSource). Two
 * {@code AuditPublisher} beans existed and the {@code @Primary} one won the injection point.
 *
 * <p>The consequence would not have been a harmless duplicate log line. {@code DbAuditPublisher.publish}
 * <b>INSERTs into {@code audit_log}</b>. So every audited write would produce <i>two</i> rows
 * carrying the <i>same</i> {@code prev_hash} and {@code row_hash} — and a chain with two
 * siblings at the same link cannot verify: the second row's {@code prev_hash} does not equal
 * its predecessor's {@code row_hash}. The tamper-evidence mechanism would report the
 * platform's own audit log as tampered, on every aggregate, for reasons no investigator
 * could distinguish from an attack. It escaped the tests because {@code AuditLogTest} is a
 * {@code @DataJpaTest} slice that installs a {@code @Primary RecordingAuditPublisher} and
 * does not load auto-configurations at all.
 *
 * <h2>Why it had not actually fired yet — and why that is not reassuring</h2>
 *
 * <p>It was <b>latent</b>, for a second bug rather than for a good reason:
 * {@code AuditPublisherAutoConfiguration} had no {@code @AutoConfigureAfter}, so its
 * {@code @ConditionalOnBean(DataSource.class)} was evaluated before
 * {@code DataSourceAutoConfiguration} had contributed one (auto-configurations order by class
 * name, and {@code com.gme.pay.audit…} sorts before {@code org.springframework…}). The DB publisher
 * was therefore never created in any real application, so the primary-bean collision never
 * happened here.
 *
 * <p>That ordering bug is fixed in the same change as this one — it had to be, because
 * auth-identity and prefunding need a durable publisher rather than a log line. Fixing it is
 * exactly what would have <b>activated</b> the double-write in config-registry. So this
 * configuration is not defensive tidying: without it, the ordering fix turns a silent no-op into
 * two rows per write and an unverifiable chain across every aggregate in this service.
 *
 * <p>The fix is to stop leaving the choice to condition-evaluation order and state it: the
 * fan-out publisher declared here is {@code @Primary}, and it is <b>never</b> a
 * {@link DbAuditPublisher}. Kafka is used when it is configured (tier 2 proper), otherwise the
 * log publisher. {@code AuditRoutingTest} pins the invariant.
 */
@Configuration
public class AuditConfig {

    /**
     * The tier-2 fan-out publisher {@link AuditLogService} publishes the sealed event to.
     *
     * <p>{@code @Primary} so it wins the injection point deterministically rather than by
     * accident of registration order. Deliberately resolves to Kafka-or-log and never to the
     * DB publisher, because tier 1 is already written by this service's own JPA path — see the
     * class javadoc for what happened when the DB publisher won.
     */
    /**
     * Takes ownership of the {@link DbAuditPublisher} bean definition, <b>without</b>
     * {@code @Primary}.
     *
     * <p>{@link com.gme.pay.audit.AuditPublisherAutoConfiguration} declares its own copy as
     * {@code @Primary @ConditionalOnBean(DataSource.class) @ConditionalOnMissingBean(DbAuditPublisher.class)}.
     * Because user configuration is registered before auto-configuration, declaring it here makes
     * that condition back off — which is the point: two {@code @Primary AuditPublisher} candidates
     * would leave {@link AuditLogService}'s constructor injection ambiguous and the service would
     * fail to start. Suppressing the auto-configured definition is therefore not optional
     * decoration; it is what keeps exactly one primary in the context.
     *
     * <p>The bean is still available by type for anything that wants a chained JDBC writer
     * directly, and it is deliberately NOT what {@link AuditLogService} fans out to.
     */
    @Bean
    public DbAuditPublisher dbAuditPublisher(javax.sql.DataSource dataSource) {
        return new DbAuditPublisher(dataSource);
    }

    @Bean
    @Primary
    public AuditPublisher registryAuditFanout(ObjectProvider<KafkaAuditPublisher> kafka) {
        KafkaAuditPublisher kafkaPublisher = kafka.getIfAvailable();
        if (kafkaPublisher != null) {
            // ADR-007 tier 2. Registered by AuditPublisherAutoConfiguration only when
            // spring.kafka.bootstrap-servers is set — which, as the CISO audit noted, no
            // deployed config-registry currently sets, so there is still no off-box copy of
            // the audit log. That is infrastructure (T1-6), not a wiring choice we can make
            // here; this branch means the moment Kafka IS configured, the fan-out uses it
            // without another code change.
            return kafkaPublisher;
        }
        return new LogAuditPublisher();
    }
}
