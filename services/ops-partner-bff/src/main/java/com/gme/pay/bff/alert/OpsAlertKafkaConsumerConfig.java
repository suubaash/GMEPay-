package com.gme.pay.bff.alert;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer wiring for the {@code gmepay.ops.alert} listener (alert loop #5).
 *
 * <p>The whole configuration is gated on {@code spring.kafka.bootstrap-servers} (docker-compose
 * injects {@code SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092}); when the property is absent — the local
 * default — no listener container, consumer factory or DLT producer is created, so unit slices and
 * broker-less local runs never attempt a connection. The {@link OpsAlertStore} still works standalone,
 * so a no-broker deployment simply retains no alerts (documented fallback). Mirrors
 * {@code RevenueLedgerKafkaConsumerConfig}.
 *
 * <ul>
 *   <li><b>Ack mode:</b> {@code MANUAL} — the listener acks only after the alert is stored.</li>
 *   <li><b>Poison handling:</b> {@link DefaultErrorHandler} retries each failing record
 *       {@value #MAX_DELIVERY_ATTEMPTS} times, then a {@link DeadLetterPublishingRecoverer} forwards
 *       it to {@code gmepay.ops.alert.DLT} so the partition never wedges on a bad record.</li>
 * </ul>
 */
@Configuration
@EnableKafka
@ConditionalOnProperty("spring.kafka.bootstrap-servers")
@EnableConfigurationProperties(KafkaProperties.class)
public class OpsAlertKafkaConsumerConfig {

    /** Bean name referenced from {@code @KafkaListener(containerFactory = ...)}. */
    public static final String LISTENER_CONTAINER_FACTORY = "opsAlertKafkaListenerContainerFactory";

    /** Total processing attempts per record (1 initial + 2 retries) before the DLT. */
    public static final int MAX_DELIVERY_ATTEMPTS = 3;

    static final String DLT_TEMPLATE_BEAN_NAME = "opsAlertDltKafkaTemplate";

    @Bean
    public ConsumerFactory<String, String> opsAlertConsumerFactory(KafkaProperties properties) {
        Map<String, Object> config = new HashMap<>(properties.buildConsumerProperties((SslBundles) null));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.putIfAbsent(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean(name = DLT_TEMPLATE_BEAN_NAME)
    public KafkaTemplate<String, String> opsAlertDltKafkaTemplate(KafkaProperties properties) {
        Map<String, Object> config = new HashMap<>(properties.buildProducerProperties((SslBundles) null));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }

    @Bean
    public DefaultErrorHandler opsAlertKafkaErrorHandler(
            @Qualifier(DLT_TEMPLATE_BEAN_NAME) KafkaTemplate<String, String> dltTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(dltTemplate);
        return new DefaultErrorHandler(recoverer, new FixedBackOff(0L, MAX_DELIVERY_ATTEMPTS - 1L));
    }

    @Bean(name = LISTENER_CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, String> opsAlertKafkaListenerContainerFactory(
            ConsumerFactory<String, String> opsAlertConsumerFactory,
            DefaultErrorHandler opsAlertKafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(opsAlertConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setCommonErrorHandler(opsAlertKafkaErrorHandler);
        return factory;
    }

    @Bean
    public OpsAlertKafkaConsumer opsAlertKafkaConsumer(OpsAlertEventHandler handler) {
        return new OpsAlertKafkaConsumer(handler);
    }
}
