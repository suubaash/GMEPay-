package com.gme.pay.events.kafka;

import com.gme.pay.events.EventPublisher;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
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

import java.util.HashMap;
import java.util.Map;

/**
 * Auto-configures a {@link KafkaEventPublisher} when Kafka is on the classpath <em>and</em>
 * {@code spring.kafka.bootstrap-servers} is set. When either is missing this back-off
 * leaves the consuming service on its own {@link EventPublisher} bean (typically
 * {@code LogEventPublisher}) — no broker, no Kafka beans.
 *
 * <p>The publisher gets a dedicated, hardened producer (rather than the service's general
 * Boot-configured {@code KafkaTemplate}): {@code acks=all} and
 * {@code enable.idempotence=true} are forced so domain events survive broker fail-over
 * without duplication, whatever the service sets under {@code spring.kafka.producer.*}.
 *
 * <p>{@code @Primary} makes this implementation win over a service-local
 * {@code LogEventPublisher} bean without the service changing any wiring.
 */
@AutoConfiguration
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty("spring.kafka.bootstrap-servers")
@EnableConfigurationProperties(KafkaProperties.class)
public class KafkaEventPublisherAutoConfiguration {

    static final String TEMPLATE_BEAN_NAME = "gmepayEventKafkaTemplate";

    @Bean(name = TEMPLATE_BEAN_NAME)
    @ConditionalOnMissingBean(name = TEMPLATE_BEAN_NAME)
    public KafkaTemplate<String, String> gmepayEventKafkaTemplate(KafkaProperties properties) {
        Map<String, Object> config = new HashMap<>(properties.buildProducerProperties((SslBundles) null));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // Non-negotiable durability settings for domain events (see class javadoc).
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(KafkaEventPublisher.class)
    public KafkaEventPublisher kafkaEventPublisher(
            @Qualifier(TEMPLATE_BEAN_NAME) KafkaTemplate<String, String> gmepayEventKafkaTemplate) {
        return new KafkaEventPublisher(gmepayEventKafkaTemplate);
    }
}
