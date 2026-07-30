package com.gme.pay.ledger.consumer;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
 * Kafka consumer wiring for the {@code gmepay.payment.approved} listener (async revenue capture).
 *
 * <p>The whole configuration is gated on {@code spring.kafka.bootstrap-servers} (docker-compose
 * injects {@code SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092}); when the property is absent — the local
 * default — no listener container, consumer factory or DLT producer is created, so unit slices stay
 * broker-free and the outbox path is unaffected.
 *
 * <ul>
 *   <li><b>Ack mode:</b> {@code MANUAL} — the listener acks only after the revenue row is persisted.</li>
 *   <li><b>Poison handling:</b> {@link DefaultErrorHandler} retries each failing record
 *       {@value #MAX_DELIVERY_ATTEMPTS} times in total, then a {@link DeadLetterPublishingRecoverer}
 *       forwards it to {@code gmepay.payment.approved.DLT} and the offset is committed, so the
 *       partition never wedges on a bad record.</li>
 * </ul>
 *
 * <p>Bean names are revenue-ledger-specific to avoid any collision should this module ever share a
 * context with another service's consumer config.
 */
@Configuration
@EnableKafka
@ConditionalOnProperty("spring.kafka.bootstrap-servers")
@EnableConfigurationProperties(KafkaProperties.class)
public class RevenueLedgerKafkaConsumerConfig {

    /** Bean name referenced from {@code @KafkaListener(containerFactory = ...)}. */
    public static final String LISTENER_CONTAINER_FACTORY = "revenueLedgerKafkaListenerContainerFactory";

    /** Total processing attempts per record (1 initial + 2 retries) before the DLT. */
    public static final int MAX_DELIVERY_ATTEMPTS = 3;

    static final String DLT_TEMPLATE_BEAN_NAME = "revenueLedgerDltKafkaTemplate";

    @Bean
    public ConsumerFactory<String, String> revenueLedgerConsumerFactory(KafkaProperties properties) {
        Map<String, Object> config = new HashMap<>(properties.buildConsumerProperties((SslBundles) null));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // MANUAL ack mode requires auto-commit off; offsets move only via Acknowledgment.
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // New consumer group starts from the beginning of the topic (no missed events), unless the
        // service explicitly overrides spring.kafka.consumer.auto-offset-reset.
        config.putIfAbsent(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(config);
    }

    /** Dedicated String/String producer for dead-lettering (decoupled from the domain-event publisher). */
    @Bean(name = DLT_TEMPLATE_BEAN_NAME)
    public KafkaTemplate<String, String> revenueLedgerDltKafkaTemplate(KafkaProperties properties) {
        Map<String, Object> config = new HashMap<>(properties.buildProducerProperties((SslBundles) null));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }

    @Bean
    public DefaultErrorHandler revenueLedgerKafkaErrorHandler(
            @Qualifier(DLT_TEMPLATE_BEAN_NAME) KafkaTemplate<String, String> dltTemplate) {
        // Default destination resolver: <topic>.DLT, same partition as the source record.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(dltTemplate);
        // FixedBackOff(interval, maxRetries): MAX_DELIVERY_ATTEMPTS - 1 retries after the initial
        // attempt, no delay (the retry budget is for transient DB errors; poison records fail fast).
        return new DefaultErrorHandler(recoverer, new FixedBackOff(0L, MAX_DELIVERY_ATTEMPTS - 1L));
    }

    /**
     * T3-11 follow-up 3: {@code concurrency} is read from configuration instead of being left at
     * Spring's default of 1.
     *
     * <p>The defect this closes is not "the value was 1", it is that the value was <b>unreadable</b>.
     * This factory is hand-built, and Boot applies {@code spring.kafka.listener.concurrency} only to
     * its own <em>auto-configured</em> factory — so an operator could set the property, watch it
     * resolve in {@code /actuator/env}, and change nothing at all. That is worse than a bad default,
     * because it looks like a lever. T3-11 fixed notification-webhook's factory and left this one and
     * prefunding's; this is the same one-line fix, deliberately identical so the four factories cannot
     * drift apart again.
     *
     * <p><b>Capped by partitions, not by this number.</b> Kafka assigns whole partitions to consumers,
     * so N threads against a 1-partition topic leaves N-1 idle — which is also why adding replicas did
     * nothing here. The default of 3 matches {@code KAFKA_NUM_PARTITIONS} in {@code docker-compose.yml}
     * and {@code SPRING_KAFKA_LISTENER_CONCURRENCY} in the Helm ABI ConfigMap. Raising one without the
     * other buys nothing.
     *
     * <p><b>Ordering is preserved where it matters.</b> Kafka guarantees order per partition and the
     * producer keys by aggregate id, so every event for one payment lands on one partition and is still
     * handled in order by one thread. Concurrency reorders across payments only, and nothing in the
     * revenue-capture path depends on that.
     *
     * <p>A non-positive value clamps to 1 rather than silently stopping revenue capture entirely.
     */
    @Bean(name = LISTENER_CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, String> revenueLedgerKafkaListenerContainerFactory(
            ConsumerFactory<String, String> revenueLedgerConsumerFactory,
            DefaultErrorHandler revenueLedgerKafkaErrorHandler,
            @Value("${spring.kafka.listener.concurrency:3}") int concurrency) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(revenueLedgerConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setCommonErrorHandler(revenueLedgerKafkaErrorHandler);
        factory.setConcurrency(Math.max(1, concurrency));
        return factory;
    }

    @Bean
    public PaymentApprovedKafkaConsumer paymentApprovedKafkaConsumer(PaymentApprovedEventHandler handler) {
        return new PaymentApprovedKafkaConsumer(handler);
    }

    @Bean
    public PaymentReversedKafkaConsumer paymentReversedKafkaConsumer(PaymentReversedEventHandler handler) {
        return new PaymentReversedKafkaConsumer(handler);
    }
}
