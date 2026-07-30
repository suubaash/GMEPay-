package com.gme.pay.audit;

import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Spring Boot auto-configuration for {@link AuditPublisher} implementations.
 *
 * <h3>Precedence rules (highest to lowest)</h3>
 * <ol>
 *   <li><b>{@link DbAuditPublisher} ({@code @Primary})</b> — active whenever a
 *       {@link DataSource} bean is present. Writes each event to the {@code audit_log}
 *       table (durable, hash-chained). This is the default production configuration
 *       for every service that has a datasource.</li>
 *   <li><b>{@link KafkaAuditPublisher}</b> — active additionally when
 *       {@code spring.kafka.bootstrap-servers} is set. Fans out each event to the Kafka
 *       topic {@code gmepay.audit.<aggregateType>} (ADR-007 tier 2). Not {@code @Primary}
 *       — registered as an extra bean for services that want to compose both tiers.</li>
 *   <li><b>{@link LogAuditPublisher}</b> — fallback when no {@link AuditPublisher} is
 *       present (no DataSource and no Kafka). Emits each event at {@code INFO} level
 *       (ADR-007 Slice 1 / local-dev behaviour).</li>
 * </ol>
 *
 * <h3>Registration</h3>
 * <p>This class is declared in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * so Boot picks it up automatically.
 */
/*
 * ORDERING IS LOAD-BEARING (gap T5-1).
 *
 * `@ConditionalOnBean(DataSource.class)` on `dbAuditPublisher` is evaluated when THIS
 * auto-configuration is processed. Auto-configurations are ordered by class name unless told
 * otherwise, and `com.gme.pay.audit.AuditPublisherAutoConfiguration` sorts BEFORE
 * `org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration`. So in a real
 * application — where the DataSource comes from auto-configuration rather than from user config —
 * the condition was evaluated before any DataSource bean definition existed, found none, and
 * `DbAuditPublisher` was NEVER CREATED. `LogAuditPublisher` silently took over as the fallback.
 *
 * The effect was a service that looked audited while writing log lines instead of hash-chained
 * rows: durable-looking wiring, no durable rows, no error, nothing in `audit_log`. It only worked
 * in tests, because a test that declares its own `DataSource` @Bean registers it as user config,
 * i.e. before auto-configuration runs — so every test of this class passed while production did
 * the opposite.
 *
 * `@AutoConfigureAfter(DataSourceAutoConfiguration.class)` is the fix. Do not remove it, and do not
 * assume a test that supplies its own DataSource bean proves this path works — see
 * `DbAuditPublisherAutoConfigOrderingTest`, which drives it through the real
 * `DataSourceAutoConfiguration` for exactly that reason.
 */
@AutoConfiguration(after = org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(KafkaProperties.class)
public class AuditPublisherAutoConfiguration {

    /** Stable bean name for the dedicated audit {@link KafkaTemplate}. */
    public static final String AUDIT_KAFKA_TEMPLATE_BEAN = "gmepayAuditKafkaTemplate";

    /**
     * Durable, hash-chained DB publisher — {@code @Primary} when a DataSource is present.
     */
    @Bean
    @Primary
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(DbAuditPublisher.class)
    public DbAuditPublisher dbAuditPublisher(DataSource dataSource) {
        return new DbAuditPublisher(dataSource);
    }

    /**
     * Dedicated {@link KafkaTemplate} for audit fan-out. Created only when
     * {@code spring.kafka.bootstrap-servers} is set and {@code spring-kafka} is on the
     * classpath. Hardened with {@code acks=all} + {@code enable.idempotence=true}.
     */
    @Bean(name = AUDIT_KAFKA_TEMPLATE_BEAN)
    @ConditionalOnClass(KafkaTemplate.class)
    @ConditionalOnProperty("spring.kafka.bootstrap-servers")
    @ConditionalOnMissingBean(name = AUDIT_KAFKA_TEMPLATE_BEAN)
    public KafkaTemplate<String, String> gmepayAuditKafkaTemplate(KafkaProperties kafkaProperties) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildProducerProperties((SslBundles) null));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }

    /**
     * Kafka fan-out publisher — registered when bootstrap-servers is configured and the
     * audit KafkaTemplate bean is present.
     */
    @Bean
    @ConditionalOnClass(KafkaTemplate.class)
    @ConditionalOnProperty("spring.kafka.bootstrap-servers")
    @ConditionalOnBean(name = AUDIT_KAFKA_TEMPLATE_BEAN)
    @ConditionalOnMissingBean(KafkaAuditPublisher.class)
    public KafkaAuditPublisher kafkaAuditPublisher(
            @Qualifier(AUDIT_KAFKA_TEMPLATE_BEAN)
            KafkaTemplate<String, String> auditKafkaTemplate) {
        return new KafkaAuditPublisher(auditKafkaTemplate);
    }

    /**
     * Log-only fallback — active when no {@link AuditPublisher} is present.
     */
    @Bean
    @ConditionalOnMissingBean(AuditPublisher.class)
    public LogAuditPublisher logAuditPublisher() {
        return new LogAuditPublisher();
    }
}
